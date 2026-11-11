import type { BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import { createIncident, type ApiFailure, type FieldError } from '@urbanpulse/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import { colors, fontSizes, spacing } from '@urbanpulse/ui';

import { BaseMap } from '../components/BaseMap';
import { Button, MIN_TOUCH_SIZE } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { LocationIndicator } from '../components/LocationIndicator';
import { TextField } from '../components/TextField';
import { useIncidentLocation } from '../hooks/useIncidentLocation';
import { locationToReport, type MapPin } from '../location/report-location';
import { incidentKeys, useCategories } from '../incidents/incident-queries';
import type { MainTabsParamList } from '../navigation/MainTabs';
import { ApiCallError } from '../query-client';
import { categoriesMessage, reportIncidentMessage, UNEXPECTED_ERROR } from '../session/messages';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

type Props = BottomTabScreenProps<MainTabsParamList, 'NewReport'>;

/** Message of the per-field errors that are not shown next to an input (`location.*`). */
const FIELD_BANNER = 'Check the highlighted fields.';

export const MAP_HINT_WITH_GPS = 'The incident will use your GPS position.';
export const MAP_HINT_WITHOUT_GPS =
  'Without the map, the incident needs your GPS position: try again or allow the location.';

/**
 * Main screen for reporting an incident (RF03, RF04). The position is the pin in the centre of the
 * map, which starts on the GPS fix or, without one, on Málaga: a citizen who rejects the location
 * can still report. The reporter is the session's user: the API takes it from the access token,
 * never from the body.
 */
export function NewIncidentScreen({ navigation }: Props) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const location = useIncidentLocation();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});
  const [error, setError] = useState<string | null>(null);
  const [pin, setPin] = useState<MapPin | null>(null);

  const submittingRef = useRef(false);

  const categories = useCategories();

  const reportedLocation = locationToReport(location.gpsFix, pin);
  const fromGps = location.gpsFix !== null && (pin === null || pin.onGpsFix);
  // Waiting for the first fix keeps a quick tap from sending the default pin on Málaga.
  const canSubmit = !location.isLoading && !isSubmitting && reportedLocation !== null;

  async function submit() {
    // Guards the button too: `isSubmitting` plus a tap before the state settles would send it twice.
    if (isSubmitting || reportedLocation === null) {
      return;
    }
    if (submittingRef.current) {
      return;
    }
    if (title.trim().length === 0) {
      setFieldErrors({ title: 'Enter a title for the incident.' });
      return;
    }
    if (description.trim().length === 0) {
      setFieldErrors({ description: 'Enter a description of the incident.' });
      return;
    }
    submittingRef.current = true;
    setIsSubmitting(true);
    setFieldErrors({});
    setError(null);
    try {
      const result = await createIncident(api, {
        title: title.trim(),
        description: description.trim(),
        ...(categoryId !== null ? { categoryId } : {}),
        location: reportedLocation,
      });
      if (!result.ok) {
        showProblems(result.failure, result.fieldErrors);
        return;
      }
      // The new report must show in "My reports" without waiting for the cache to go stale.
      void queryClient.invalidateQueries({ queryKey: incidentKeys.all });
      setTitle('');
      setDescription('');
      setCategoryId(null);
      const incidentId = result.data.id;
      // Photos go after the report, never with it: a failed upload cannot lose the report (#44).
      Alert.alert(
        'Success',
        'Incident reported. You can add photos now, and follow its progress in "My reports".',
        [
          { text: 'My reports', onPress: () => navigation.navigate('MyReports') },
          {
            text: 'Add photos',
            onPress: () =>
              navigation.navigate('MyReports', {
                screen: 'AddPhotos',
                params: { incidentId },
                // The list stays below, so going back from the photos shows the new report.
                initial: false,
              }),
          },
        ],
      );
    } catch (unexpected) {
      // A bug, not a user mistake: log it and let the user retry.
      console.error(unexpected);
      setError(UNEXPECTED_ERROR);
    } finally {
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }

  function showProblems(failure: ApiFailure, invalidFields: readonly FieldError[]) {
    if (invalidFields.length === 0) {
      setError(reportIncidentMessage(failure));
      return;
    }
    const messages: Record<string, string> = {};
    const locationMessages: string[] = [];
    for (const { field, message } of invalidFields) {
      if (field.startsWith('location.')) {
        locationMessages.push(message);
      } else {
        messages[field] = message;
      }
    }
    setFieldErrors(messages);
    setError(
      locationMessages.length > 0
        ? `Check the location: ${locationMessages.join(' ')}`
        : FIELD_BANNER,
    );
  }

  return (
    <KeyboardAvoidingView
      style={screenStyles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView contentContainerStyle={screenStyles.form} keyboardShouldPersistTaps="handled">
        <Text style={screenStyles.title}>Report an incident</Text>

        <BaseMap
          gpsFix={location.gpsFix}
          onPinChange={setPin}
          unavailableHint={location.gpsFix !== null ? MAP_HINT_WITH_GPS : MAP_HINT_WITHOUT_GPS}
        />

        <LocationIndicator
          location={reportedLocation}
          fromGps={fromGps}
          isLoading={location.isLoading}
          error={location.error}
          permissionDenied={location.permissionDenied}
          onUseMyPosition={() => void location.refreshLocation()}
          onOpenSettings={() => void location.openSettings()}
        />

        <FormMessage message={error} tone="error" />

        <View style={styles.field}>
          <TextField
            label="Title"
            value={title}
            onChangeText={setTitle}
            placeholder="Short summary"
            maxLength={120}
            accessibilityHint={fieldErrors.title}
          />
          {fieldErrors.title ? <Text style={styles.fieldError}>{fieldErrors.title}</Text> : null}
        </View>

        <View style={styles.field}>
          <Text style={styles.label}>Description</Text>
          <TextInput
            accessibilityLabel="Description"
            accessibilityHint={fieldErrors.description}
            multiline
            numberOfLines={4}
            placeholder="What happens and where"
            placeholderTextColor={colors.textMuted}
            maxLength={2000}
            style={styles.textArea}
            value={description}
            onChangeText={setDescription}
          />
          {fieldErrors.description ? (
            <Text style={styles.fieldError}>{fieldErrors.description}</Text>
          ) : null}
        </View>

        <View style={styles.field}>
          <Text style={styles.label}>Category (optional)</Text>
          {/* Loaded categories stay on screen when a later refresh fails. */}
          {categories.isPending ? (
            <ActivityIndicator accessibilityLabel="Loading the categories" />
          ) : categories.data === undefined ? (
            <View style={styles.field}>
              <Text style={styles.fieldError}>
                {categories.error instanceof ApiCallError
                  ? categoriesMessage(categories.error.failure)
                  : UNEXPECTED_ERROR}
              </Text>
              <Button
                label={categories.isFetching ? 'Trying again…' : 'Try again'}
                variant="secondary"
                disabled={categories.isFetching}
                onPress={() => void categories.refetch()}
              />
            </View>
          ) : (
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.categoryRow}
            >
              <Pressable
                accessibilityRole="button"
                accessibilityState={{ selected: categoryId === null }}
                onPress={() => setCategoryId(null)}
                style={[styles.categoryChip, categoryId === null && styles.categoryChipSelected]}
              >
                <Text
                  style={[
                    styles.categoryLabel,
                    categoryId === null && styles.categoryLabelSelected,
                  ]}
                >
                  None
                </Text>
              </Pressable>
              {categories.data.map((category) => {
                const selected = categoryId === category.id;
                return (
                  <Pressable
                    key={category.id}
                    accessibilityRole="button"
                    accessibilityState={{ selected }}
                    onPress={() => setCategoryId(category.id)}
                    style={[styles.categoryChip, selected && styles.categoryChipSelected]}
                  >
                    <Text style={[styles.categoryLabel, selected && styles.categoryLabelSelected]}>
                      {category.name}
                    </Text>
                  </Pressable>
                );
              })}
            </ScrollView>
          )}
          {fieldErrors.categoryId ? (
            <Text style={styles.fieldError}>{fieldErrors.categoryId}</Text>
          ) : null}
        </View>

        <Button
          label={isSubmitting ? 'Sending…' : 'Submit'}
          onPress={submit}
          disabled={!canSubmit}
        />
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  field: {
    gap: spacing.xs,
  },
  label: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  fieldError: {
    color: colors.danger,
    fontSize: 14,
  },
  textArea: {
    minHeight: 96,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.sm,
    borderWidth: 1,
    borderColor: colors.textMuted,
    borderRadius: 8,
    color: colors.text,
    fontSize: fontSizes.body,
    textAlignVertical: 'top',
  },
  pickerPlaceholder: {
    minHeight: 48,
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
    borderWidth: 1,
    borderColor: colors.textMuted,
    borderRadius: 8,
    backgroundColor: colors.surface,
  },
  placeholderText: {
    color: colors.textMuted,
    fontSize: fontSizes.body,
  },
  categoryRow: {
    gap: spacing.xs,
    paddingVertical: spacing.xs,
  },
  categoryChip: {
    minHeight: MIN_TOUCH_SIZE,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: colors.textMuted,
    backgroundColor: colors.background,
  },
  categoryChipSelected: {
    borderColor: colors.primary,
    backgroundColor: colors.primary,
  },
  categoryLabel: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
  categoryLabelSelected: {
    color: colors.background,
    fontWeight: '600',
  },
});
