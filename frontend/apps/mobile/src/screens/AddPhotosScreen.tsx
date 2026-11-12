import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { ApiFailure, LocalFile } from '@urbanpulse/api-client';
import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { useRef, useState } from 'react';
import { Image, Linking, ScrollView, StyleSheet, Text, View } from 'react-native';

import { pickPhoto, type PhotoSource } from '../attachments/photo-picker';
import { isTransient, RETRY_DELAYS_MS, withRetries } from '../attachments/upload-retry';
import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import type { MyReportsStackParamList } from '../navigation/MyReportsStack';
import { UNEXPECTED_ERROR, uploadPhotoMessage } from '../session/messages';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

type Props = NativeStackScreenProps<MyReportsStackParamList, 'AddPhotos'>;

/** A photo the API stored, shown from the device's own copy. */
interface UploadedPhoto {
  attachmentId: string;
  uri: string;
}

const PREPARE_FAILED = 'The photo could not be prepared. Try again or choose another one.';

/**
 * Optional photos of a report the citizen already sent (RF05). The incident exists before the
 * first upload starts, so nothing here can lose the report: a failed upload leaves it without
 * the photo, says so, and lets the citizen try again.
 */
export function AddPhotosScreen({ route }: Props) {
  const { incidentId } = route.params;
  const { uploadAttachment, markSession } = useSession();
  const [selected, setSelected] = useState<LocalFile | null>(null);
  const [uploaded, setUploaded] = useState<readonly UploadedPhoto[]>([]);
  const [progress, setProgress] = useState<number | null>(null);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cameraDenied, setCameraDenied] = useState(false);
  // State updates are not immediate: a second tap in the same frame must not start a second call.
  const busyRef = useRef(false);

  const uploading = progress !== null;

  async function choose(source: PhotoSource) {
    if (busyRef.current) {
      return;
    }
    busyRef.current = true;
    setError(null);
    setFailure(null);
    try {
      const outcome = await pickPhoto(source);
      setCameraDenied(outcome.kind === 'denied');
      if (outcome.kind === 'photo') {
        setSelected(outcome.photo);
      }
    } catch (unexpected) {
      console.error('Could not prepare the photo', unexpected);
      setError(PREPARE_FAILED);
    } finally {
      busyRef.current = false;
    }
  }

  async function upload() {
    if (busyRef.current || selected === null) {
      return;
    }
    busyRef.current = true;
    const photo = selected;
    setError(null);
    setFailure(null);
    setProgress(0);
    // Every attempt belongs to the session of the first: if that session ends during the wait
    // between attempts, no retry is sent with the session of whoever signs in next (#259).
    const session = markSession();
    try {
      const result = await withRetries(() => {
        // Each attempt sends the whole file again, so its progress starts from zero.
        setProgress(0);
        return uploadAttachment(incidentId, photo, setProgress, session);
      }, RETRY_DELAYS_MS);
      if (result.ok) {
        // A retry of a photo already stored answers with the same attachment: show it once.
        setUploaded((photos) =>
          photos.some((p) => p.attachmentId === result.data.id)
            ? photos
            : [...photos, { attachmentId: result.data.id, uri: photo.uri }],
        );
        setSelected(null);
        return;
      }
      // Kept in the device log, for support, together with the incident it belongs to.
      console.warn('Photo upload failed', { incidentId, failure: result.failure });
      setFailure(result.failure);
      setError(uploadPhotoMessage(result.failure));
    } catch (unexpected) {
      console.error('Photo upload failed', unexpected);
      setError(UNEXPECTED_ERROR);
    } finally {
      busyRef.current = false;
      setProgress(null);
    }
  }

  return (
    <ScrollView style={screenStyles.flex} contentContainerStyle={screenStyles.form}>
      <Text style={screenStyles.body}>
        Your report is saved. Photos are optional and help the city council see the problem.
      </Text>

      <View style={styles.sources}>
        <Button
          label="Take a photo"
          variant="secondary"
          disabled={uploading}
          onPress={() => void choose('camera')}
        />
        <Button
          label="Choose from gallery"
          variant="secondary"
          disabled={uploading}
          onPress={() => void choose('library')}
        />
      </View>

      {cameraDenied && (
        <View style={styles.block}>
          <FormMessage
            tone="info"
            message="UrbanPulse cannot use the camera. Allow it in the device settings, or choose a photo from the gallery."
          />
          <Button
            label="Open settings"
            variant="secondary"
            onPress={() => void Linking.openSettings()}
          />
        </View>
      )}

      <FormMessage message={error} tone="error" />

      {selected !== null && (
        <View style={styles.block}>
          <Image
            source={{ uri: selected.uri }}
            style={styles.preview}
            resizeMode="cover"
            accessibilityLabel="Photo to upload"
          />
          {progress !== null && <UploadProgress fraction={progress} />}
          <Button
            label={
              uploading
                ? 'Uploading…'
                : failure !== null && isTransient(failure)
                  ? 'Try again'
                  : 'Upload photo'
            }
            disabled={uploading}
            onPress={() => void upload()}
          />
        </View>
      )}

      {uploaded.length > 0 && (
        <View style={styles.block}>
          <Text style={styles.sectionTitle} accessibilityRole="header">
            Uploaded photos ({uploaded.length})
          </Text>
          <View style={styles.thumbnails}>
            {uploaded.map((photo, index) => (
              <Image
                key={photo.attachmentId}
                source={{ uri: photo.uri }}
                style={styles.thumbnail}
                accessibilityLabel={`Uploaded photo ${index + 1}`}
              />
            ))}
          </View>
        </View>
      )}
    </ScrollView>
  );
}

/** The fraction of the file sent, as reported by the upload itself. */
function UploadProgress({ fraction }: { fraction: number }) {
  const percent = Math.round(fraction * 100);
  return (
    <View
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel="Upload progress"
      accessibilityValue={{ min: 0, max: 100, now: percent }}
      style={styles.block}
    >
      <View style={styles.track}>
        <View style={[styles.bar, { width: `${percent}%` }]} />
      </View>
      <Text style={screenStyles.note}>{percent}%</Text>
    </View>
  );
}

const THUMBNAIL_SIZE = 96;

const styles = StyleSheet.create({
  sources: {
    gap: spacing.sm,
  },
  block: {
    gap: spacing.sm,
  },
  preview: {
    width: '100%',
    aspectRatio: 4 / 3,
    borderRadius: 8,
    backgroundColor: colors.surface,
  },
  track: {
    height: spacing.sm,
    borderRadius: spacing.xs,
    backgroundColor: colors.surface,
    overflow: 'hidden',
  },
  bar: {
    height: '100%',
    backgroundColor: colors.primary,
  },
  sectionTitle: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: 'bold',
  },
  thumbnails: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  thumbnail: {
    width: THUMBNAIL_SIZE,
    height: THUMBNAIL_SIZE,
    borderRadius: 8,
    backgroundColor: colors.surface,
  },
});
