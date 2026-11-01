import type { BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import { createIncident, type ApiFailure, type FieldError } from '@urbanpulse/api-client';
import { useState } from 'react';
import {
  Alert,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import { colors, fontSizes, spacing } from '@urbanpulse/ui';

import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { LocationIndicator } from '../components/LocationIndicator';
import { TextField } from '../components/TextField';
import { useIncidentLocation } from '../hooks/useIncidentLocation';
import type { MainTabsParamList } from '../navigation/MainTabs';
import { reportIncidentMessage, UNEXPECTED_ERROR } from '../session/messages';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

type Props = BottomTabScreenProps<MainTabsParamList, 'NewReport'>;

/** Message of the per-field errors that are not shown next to an input (`location.*`). */
const FIELD_BANNER = 'Check the highlighted fields.';

/**
 * Main screen for reporting an incident (RF03, RF04). The reporter is the session's user: the API
 * takes it from the access token, never from the body.
 */
export function NewIncidentScreen({ navigation }: Props) {
  const { api } = useSession();
  const location = useIncidentLocation();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Readonly<Record<string, string>>>({});
  const [error, setError] = useState<string | null>(null);

  const canSubmit =
    !location.isLoading &&
    !isSubmitting &&
    location.latitude !== null &&
    location.longitude !== null &&
    title.trim().length > 0 &&
    description.trim().length > 0;

  async function submit() {
    // Guards the button too: `isSubmitting` plus a tap before the state settles would send it twice.
    if (isSubmitting || location.latitude === null || location.longitude === null) {
      return;
    }
    setIsSubmitting(true);
    setFieldErrors({});
    setError(null);
    try {
      const result = await createIncident(api, {
        title: title.trim(),
        description: description.trim(),
        ...(categoryId !== null ? { categoryId } : {}),
        location: {
          latitude: location.latitude,
          longitude: location.longitude,
          ...(location.accuracy !== null ? { accuracy: location.accuracy } : {}),
        },
      });
      if (!result.ok) {
        showProblems(result.failure, result.fieldErrors);
        return;
      }
      setTitle('');
      setDescription('');
      setCategoryId(null);
      Alert.alert('Success', 'Incident reported. Follow its progress in "My reports".', [
        { text: 'My reports', onPress: () => navigation.navigate('MyReports') },
      ]);
    } catch (unexpected) {
      // A bug, not a user mistake: log it and let the user retry.
      console.error(unexpected);
      setError(UNEXPECTED_ERROR);
    } finally {
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

        <LocationIndicator
          accuracy={location.accuracy}
          isLoading={location.isLoading}
          error={location.error}
          permissionDenied={location.permissionDenied}
          onRefresh={location.refreshLocation}
          onOpenSettings={location.openSettings}
        />

        <FormMessage message={error} tone="error" />

        <View style={styles.field}>
          <TextField
            label="Title"
            value={title}
            onChangeText={setTitle}
            placeholder="Short summary"
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
          <View style={styles.pickerPlaceholder}>
            <Text style={styles.placeholderText}>
              {categoryId
                ? `Selected: ${categoryId}`
                : 'Category picker (coming soon); an operator can classify it later'}
            </Text>
          </View>
          {fieldErrors.categoryId ? (
            <Text style={styles.fieldError}>{fieldErrors.categoryId}</Text>
          ) : null}
        </View>

        <Button
          label={isSubmitting ? 'Sending…' : 'Submit'}
          onPress={() => void submit()}
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
});
