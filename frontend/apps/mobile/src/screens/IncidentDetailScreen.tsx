import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQueryClient } from '@tanstack/react-query';
import type { components } from '@urbanpulse/api-client';
import {
  addressText,
  buildTimeline,
  CITY_COUNCIL,
  colors,
  fontSizes,
  formatInstant,
  NOT_AVAILABLE,
  spacing,
  type StatusTimelineEntry,
} from '@urbanpulse/ui';
import { useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Image,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { LoadFailure } from '../components/LoadFailure';
import { LoadingSkeleton, SkeletonBlock } from '../components/Skeleton';
import { StatusBadge } from '../components/StatusBadge';
import {
  incidentKeys,
  refetchFailedPhotos,
  useAttachmentContent,
  useIncidentDetail,
  useIncidentAttachments,
  useIncidentHistory,
} from '../incidents/incident-queries';
import type { MyReportsStackParamList } from '../navigation/MyReportsStack';
import { isNotFound } from '../query-client';
import { screenStyles } from './styles';

type Props = NativeStackScreenProps<MyReportsStackParamList, 'ReportDetail'>;

type Incident = components['schemas']['IncidentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];

/** The sections of the report, also drawn by its skeleton. */
const SECTIONS = { details: 'Details', location: 'Location' } as const;

/**
 * The fields every report has in each section, drawn while it loads: never a count to read.
 * Details: category, reported on and updated on. Location: the five of {@link IncidentData}.
 */
const SKELETON_FIELDS: Record<keyof typeof SECTIONS, number> = { details: 3, location: 5 };

/** One of the citizen's reports: its data, where it is and what has happened to it (RF06). */
export function IncidentDetailScreen({ navigation, route }: Props) {
  const { incidentId } = route.params;
  const { incident, addressWaitEnded } = useIncidentDetail(incidentId);
  const history = useIncidentHistory(incidentId);
  const photos = useIncidentAttachments(incidentId);
  const queryClient = useQueryClient();
  const [pulling, setPulling] = useState(false);

  async function refresh() {
    setPulling(true);
    try {
      // The list too, so going back does not show an older status than this detail.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: incidentKeys.all }),
        refetchFailedPhotos(queryClient),
      ]);
    } finally {
      setPulling(false);
    }
  }

  if (incident.isPending) {
    return (
      <View style={[screenStyles.flex, styles.skeletonScreen]}>
        <LoadingSkeleton what="the report">
          <IncidentDataSkeleton />
        </LoadingSkeleton>
      </View>
    );
  }
  // A failed refresh keeps the report on screen with a notice; only a 404 hides what was loaded.
  if (incident.isLoadingError || isNotFound(incident.error)) {
    return (
      <View style={screenStyles.screen}>
        {isNotFound(incident.error) ? (
          // The API answers 404 for someone else's incident too, so the text cannot say which.
          <FormMessage
            tone="info"
            message="This report was not found. It may have been removed, or it is not one of yours."
          />
        ) : (
          <LoadFailure
            what="The report"
            error={incident.error}
            retrying={incident.isFetching}
            onRetry={() => void incident.refetch()}
          />
        )}
      </View>
    );
  }

  return (
    <ScrollView
      testID="report-detail"
      style={screenStyles.flex}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={pulling} onRefresh={refresh} />}
    >
      {(incident.isRefetchError || history.isRefetchError || photos.isRefetchError) && (
        <FormMessage message="The report could not be refreshed. This is the last version loaded." />
      )}
      <IncidentData incident={incident.data} addressWaitEnded={addressWaitEnded} />
      {/* The API lets the reporter add photos only while the report waits for review (#44). */}
      {incident.data.status === 'REPORTED' && (
        <Button
          label="Add photos"
          variant="secondary"
          onPress={() => navigation.navigate('AddPhotos', { incidentId })}
        />
      )}
      <Photos photos={photos} />
      <Section title="History">
        {history.isPending ? (
          <ActivityIndicator accessibilityLabel="Loading the history" />
        ) : history.isLoadingError ? (
          <LoadFailure
            what="The history"
            error={history.error}
            retrying={history.isFetching}
            onRetry={() => void history.refetch()}
          />
        ) : (
          <Timeline changes={history.data} />
        )}
      </Section>
    </ScrollView>
  );
}

type Attachment = components['schemas']['AttachmentResponse'];

/**
 * The photos the citizen uploaded (#279). With none there is no section, and a list that fails
 * leaves the rest of the report on screen. Each photo loads on its own, so one that fails leaves
 * the others.
 */
function Photos({ photos }: { photos: ReturnType<typeof useIncidentAttachments> }) {
  // Nothing while the list loads: a header that vanishes for a report without photos moves the
  // rest of the screen.
  if (photos.isPending) {
    return null;
  }
  if (photos.isLoadingError) {
    return (
      <Section title="Photos">
        <LoadFailure
          what="The photos"
          error={photos.error}
          retrying={photos.isFetching}
          onRetry={() => void photos.refetch()}
        />
      </Section>
    );
  }
  if (photos.data.length === 0) {
    return null;
  }
  return (
    <Section title="Photos">
      <View style={styles.photos}>
        {photos.data.map((photo, index) => (
          <Photo
            key={photo.id}
            attachment={photo}
            position={index + 1}
            total={photos.data.length}
          />
        ))}
      </View>
    </Section>
  );
}

function Photo({
  attachment,
  position,
  total,
}: {
  attachment: Attachment;
  position: number;
  total: number;
}) {
  const content = useAttachmentContent(attachment.id);
  if (content.isPending) {
    return (
      <View style={styles.photoFrame}>
        <ActivityIndicator accessibilityLabel={`Loading photo ${position} of ${total}`} />
      </View>
    );
  }
  if (content.isError && isNotFound(content.error)) {
    // Trying again gets the same answer: the photo is gone.
    return (
      <View style={[styles.photoFrame, styles.photoFailure]}>
        <FormMessage message="This photo is no longer available." />
      </View>
    );
  }
  if (content.isError) {
    return (
      <View style={[styles.photoFrame, styles.photoFailure]}>
        <FormMessage message="This photo could not be loaded." />
        <Button
          label={content.isFetching ? 'Trying again…' : 'Try again'}
          variant="secondary"
          disabled={content.isFetching}
          onPress={() => void content.refetch()}
        />
      </View>
    );
  }
  return (
    <Image
      source={{ uri: content.data }}
      style={styles.photoFrame}
      resizeMode="cover"
      accessible
      accessibilityRole="image"
      accessibilityLabel={`Photo ${position} of ${total}`}
    />
  );
}

/** A `PENDING` address the screen no longer reads on its own (#436). */
const ADDRESS_NOT_ARRIVED = 'The address has not arrived yet. Pull down to check again.';

function IncidentData({
  incident,
  addressWaitEnded,
}: {
  incident: Incident;
  addressWaitEnded: boolean;
}) {
  const { location } = incident;
  return (
    <>
      <View style={styles.heading}>
        <Text style={screenStyles.title} accessibilityRole="header">
          {incident.title}
        </Text>
        <StatusBadge status={incident.status} />
      </View>
      <Text style={screenStyles.body}>{incident.description}</Text>
      <Section title={SECTIONS.details}>
        <Field label="Category" value={incident.category?.name ?? 'Not set yet'} />
        <Field label="Reported on" value={formatInstant(incident.reportedAt)} />
        <Field label="Updated on" value={formatInstant(incident.updatedAt)} />
        {incident.closedAt !== null && (
          <Field label="Closed on" value={formatInstant(incident.closedAt)} />
        )}
      </Section>
      <Section title={SECTIONS.location}>
        <Field
          label="Address"
          value={
            location.addressStatus === 'PENDING' && addressWaitEnded
              ? ADDRESS_NOT_ARRIVED
              : addressText(location.address, location.addressStatus).text
          }
          source={location.addressSource?.attribution}
        />
        <Field label="Neighbourhood" value={location.neighborhood ?? NOT_AVAILABLE} />
        <Field label="District" value={location.district ?? NOT_AVAILABLE} />
        <Field
          label="Coordinates (latitude, longitude)"
          value={`${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`}
        />
        <Field
          label="Accuracy"
          value={
            location.accuracy === null ? NOT_AVAILABLE : `± ${Math.round(location.accuracy)} m`
          }
        />
      </Section>
    </>
  );
}

/**
 * The title, the status, the description and the two sections of {@link IncidentData}, each field
 * as its label above its value, so the sections do not grow when the report arrives.
 */
function IncidentDataSkeleton() {
  return (
    <View style={styles.skeleton}>
      <View style={styles.heading}>
        <SkeletonBlock width="80%" height={fontSizes.title} />
        <SkeletonBlock width="30%" />
      </View>
      <View style={styles.section}>
        <SkeletonBlock />
        <SkeletonBlock width="90%" />
        <SkeletonBlock width="60%" />
      </View>
      {(['details', 'location'] as const).map((section) => (
        <View key={section} style={styles.section}>
          <Text style={styles.sectionTitle}>{SECTIONS[section]}</Text>
          {Array.from({ length: SKELETON_FIELDS[section] }, (_, field) => (
            <View key={field} style={styles.field}>
              <SkeletonBlock width="35%" />
              <SkeletonBlock width="60%" />
            </View>
          ))}
        </View>
      ))}
    </View>
  );
}

/**
 * The visible history, oldest first (#169). The API never tells a citizen who made a change, so
 * the city council stands for staff; the report is the citizen's own. Classification changes are
 * staff-only and never requested here.
 */
function Timeline({ changes }: { changes: StatusChange[] }) {
  return (
    <View style={styles.timeline} testID="history-timeline" accessibilityRole="list">
      {buildTimeline(changes).map(
        (entry) =>
          entry.kind === 'status' && (
            <View key={entry.key} style={styles.entry}>
              <View style={styles.entryMark} />
              <View style={styles.entryBody}>
                <Text style={styles.entryTitle}>{entry.title}</Text>
                <Text style={screenStyles.note}>
                  {formatInstant(entry.instant)} ·{' '}
                  {entry.previousStatus === null ? 'You' : CITY_COUNCIL}
                </Text>
                {entry.reason !== null && <EntryReason entry={entry} reason={entry.reason} />}
              </View>
            </View>
          ),
      )}
    </View>
  );
}

/**
 * An information request (ADR-0020: `REPORTED` to `REPORTED`) is a question from the city council
 * to the citizen; any other reason the citizen receives explains the change, such as a rejection.
 */
function EntryReason({ entry, reason }: { entry: StatusTimelineEntry; reason: string }) {
  const informationRequest = entry.previousStatus === 'REPORTED' && entry.status === 'REPORTED';
  return (
    <View style={informationRequest ? styles.message : undefined}>
      <Text style={styles.reasonLabel}>
        {informationRequest ? 'Message from the city council' : 'Reason'}
      </Text>
      <Text style={screenStyles.body}>{reason}</Text>
    </View>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle} accessibilityRole="header">
        {title}
      </Text>
      {children}
    </View>
  );
}

/**
 * @param source the attribution the licence of the value asks for, shown under it, such as the
 *   one of the address (#375)
 */
function Field({ label, value, source }: { label: string; value: string; source?: string }) {
  const announced = source ? `${label}: ${value}. ${source}` : `${label}: ${value}`;
  return (
    // One element for screen readers, so the label and its value are read together.
    <View style={styles.field} accessible accessibilityLabel={announced}>
      <Text style={screenStyles.note}>{label}</Text>
      <Text style={screenStyles.body}>{value}</Text>
      {source && <Text style={screenStyles.note}>{source}</Text>}
    </View>
  );
}

const MARK_SIZE = 10;
const PHOTO_ASPECT_RATIO = 4 / 3;

const styles = StyleSheet.create({
  content: {
    padding: spacing.md,
    gap: spacing.md,
  },
  skeletonScreen: {
    paddingVertical: spacing.md,
  },
  skeleton: {
    paddingHorizontal: spacing.md,
    gap: spacing.md,
  },
  heading: {
    gap: spacing.sm,
  },
  section: {
    gap: spacing.sm,
  },
  sectionTitle: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: 'bold',
  },
  field: {
    gap: 2,
  },
  photos: {
    gap: spacing.md,
  },
  photoFrame: {
    width: '100%',
    aspectRatio: PHOTO_ASPECT_RATIO,
    borderRadius: 8,
    backgroundColor: colors.surface,
    justifyContent: 'center',
    alignItems: 'center',
  },
  photoFailure: {
    padding: spacing.md,
    gap: spacing.sm,
  },
  timeline: {
    gap: spacing.md,
  },
  entry: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  entryMark: {
    width: MARK_SIZE,
    height: MARK_SIZE,
    marginTop: spacing.xs,
    borderRadius: MARK_SIZE / 2,
    backgroundColor: colors.primary,
  },
  entryBody: {
    flex: 1,
    gap: 2,
  },
  entryTitle: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  reasonLabel: {
    color: colors.textMuted,
    fontWeight: '600',
  },
  message: {
    marginTop: spacing.xs,
    padding: spacing.sm,
    borderRadius: 8,
    backgroundColor: colors.surface,
    gap: 2,
  },
});
