import {
  deletePushToken,
  getNotificationSettings,
  registerPushToken,
  updateNotificationSettings,
  type components,
} from '@urbanpulse/api-client';
import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { ActivityIndicator, Linking, StyleSheet, Switch, Text, View } from 'react-native';

import { Button, MIN_TOUCH_SIZE } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { LoadFailure } from '../components/LoadFailure';
import { askPushPermission, readPushToken } from '../notifications/push';
import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

type Settings = components['schemas']['NotificationSettings'];

const SETTINGS_KEY = ['notification-settings'] as const;

const CHANGE_FAILED = 'The change could not be saved. Check your connection and try again.';
const NO_TOKEN = 'This phone could not get a notification token. Try again in a moment.';
const PERMISSION_DENIED =
  'Notifications are turned off for UrbanPulse on this phone. Turn them on in the system settings, then try again.';

/** What the citizen is told after a change that did not happen; the denied permission adds a link. */
interface Notice {
  text: string;
  openSettings?: boolean;
}

/**
 * The two channels the citizen is notified through (RF14). A switch moves only after the server
 * confirms the change, and a second change waits for the first.
 */
export function NotificationSettingsSection() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const settings = useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => dataOf(getNotificationSettings(api)),
    // The screen has its own *Try again*; a retry behind it would only delay it.
    retry: false,
  });
  const [notice, setNotice] = useState<Notice | null>(null);
  // A ref, not state: two taps in the same render must not start two changes.
  const changing = useRef(false);

  async function change(task: () => Promise<Notice | null>) {
    if (changing.current) {
      return;
    }
    changing.current = true;
    setNotice(null);
    try {
      setNotice(await task());
    } catch {
      setNotice({ text: CHANGE_FAILED });
    } finally {
      changing.current = false;
    }
  }

  async function save(changes: Partial<Settings>): Promise<Notice | null> {
    const result = await updateNotificationSettings(api, changes);
    if (!result.ok) {
      return { text: CHANGE_FAILED };
    }
    queryClient.setQueryData(SETTINGS_KEY, result.data);
    return null;
  }

  async function turnPushOn(): Promise<Notice | null> {
    if ((await askPushPermission()) !== 'granted') {
      return { text: PERMISSION_DENIED, openSettings: true };
    }
    const token = await readPushToken();
    if (!token) {
      return { text: NO_TOKEN };
    }
    const registered = await registerPushToken(api, token);
    return registered.ok ? save({ push: true }) : { text: CHANGE_FAILED };
  }

  async function turnPushOff(): Promise<Notice | null> {
    const failure = await save({ push: false });
    if (failure) {
      return failure;
    }
    // The setting is already off, so the server sends nothing; the token goes only to tidy up.
    const token = await readPushToken();
    if (token) {
      await deletePushToken(api, token);
    }
    return null;
  }

  return (
    <View style={styles.section}>
      <Text accessibilityRole="header" style={styles.heading}>
        Notifications
      </Text>
      {settings.isPending ? (
        <View
          accessible
          accessibilityRole="progressbar"
          accessibilityLabel="Loading your notification settings"
        >
          <ActivityIndicator />
        </View>
      ) : settings.isError ? (
        <LoadFailure
          what="Your notification settings"
          error={settings.error}
          retrying={settings.isFetching}
          onRetry={() => void settings.refetch()}
        />
      ) : (
        <>
          <SwitchRow
            label="Push notifications"
            description="Updates about your reports on this phone."
            value={settings.data.push}
            onChange={(on) => void change(on ? turnPushOn : turnPushOff)}
          />
          <SwitchRow
            label="Email"
            description="Updates about your reports by email."
            value={settings.data.email}
            onChange={(on) => void change(() => save({ email: on }))}
          />
          <FormMessage message={notice?.text ?? null} />
          {notice?.openSettings && (
            <Button
              label="Open settings"
              variant="link"
              onPress={() => void Linking.openSettings()}
            />
          )}
        </>
      )}
    </View>
  );
}

interface SwitchRowProps {
  label: string;
  description: string;
  value: boolean;
  onChange: (value: boolean) => void;
}

function SwitchRow({ label, description, value, onChange }: SwitchRowProps) {
  return (
    <View style={styles.row}>
      <View style={styles.labels}>
        <Text style={screenStyles.body}>{label}</Text>
        <Text style={styles.description}>{description}</Text>
      </View>
      <Switch
        accessibilityLabel={label}
        value={value}
        onValueChange={onChange}
        trackColor={{ true: colors.primary }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  heading: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  row: {
    minHeight: MIN_TOUCH_SIZE,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
  },
  labels: {
    flex: 1,
  },
  description: {
    color: colors.textMuted,
    fontSize: fontSizes.body,
  },
});
