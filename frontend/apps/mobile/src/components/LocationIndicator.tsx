import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';

import { MIN_TOUCH_SIZE } from './Button';

export interface LocationIndicatorProps {
  accuracy: number | null;
  isLoading: boolean;
  error: string | null;
  permissionDenied: boolean;
  onRefresh: () => void;
  onOpenSettings: () => void;
}

/** Shows the GPS state: loading, permission denied, or acquired accuracy. */
export function LocationIndicator({
  accuracy,
  isLoading,
  error,
  permissionDenied,
  onRefresh,
  onOpenSettings,
}: LocationIndicatorProps) {
  if (isLoading) {
    return (
      <View style={styles.row} accessibilityRole="progressbar">
        <ActivityIndicator />
        <Text style={styles.text}>Getting your location…</Text>
      </View>
    );
  }

  if (permissionDenied) {
    return (
      <View style={styles.block}>
        <Text style={styles.title}>Location is needed</Text>
        <Text style={styles.text}>
          UrbanPulse uses your position to place the incident on the map. Enable location in the
          device settings to report a problem.
        </Text>
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <Pressable
          accessibilityRole="button"
          onPress={onOpenSettings}
          style={({ pressed }) => [styles.button, pressed && styles.dimmed]}
        >
          <Text style={styles.buttonLabel}>Open settings</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={styles.block}>
      <View style={styles.row}>
        <Text style={styles.text}>
          Accuracy: {accuracy !== null ? `${Math.round(accuracy)} m` : 'unknown'}
        </Text>
        <Pressable
          accessibilityRole="button"
          onPress={onRefresh}
          style={({ pressed }) => [styles.button, pressed && styles.dimmed]}
        >
          <Text style={styles.buttonLabel}>Refresh</Text>
        </Pressable>
      </View>
      {error ? <Text style={styles.error}>{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  block: {
    gap: spacing.sm,
  },
  title: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  text: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
  error: {
    color: colors.danger ?? colors.text,
    fontSize: 14,
  },
  button: {
    minHeight: MIN_TOUCH_SIZE,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: colors.textMuted,
    backgroundColor: colors.background,
  },
  buttonLabel: {
    color: colors.primary,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  dimmed: {
    opacity: 0.6,
  },
});
