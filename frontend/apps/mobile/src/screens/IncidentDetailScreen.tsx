import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQueryClient } from '@tanstack/react-query';
import type { components } from '@urbanpulse/api-client';
import {
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
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { LoadFailure } from '../components/LoadFailure';
import { StatusBadge } from '../components/StatusBadge';
import { incidentKeys, useIncident, useIncidentHistory } from '../incidents/incident-queries';
import type { MyReportsStackParamList } from '../navigation/MyReportsStack';
import { isNotFound } from '../query-client';
import { screenStyles } from './styles';

type Props = NativeStackScreenProps<MyReportsStackParamList, 'ReportDetail'>;

type Incident = components['schemas']['IncidentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];

/** One of the citizen's reports: its data, where it is and what has happened to it (RF06). */
export function IncidentDetailScreen({ navigation, route }: Props) {
  const { incidentId } = route.params;
  const incident = useIncident(incidentId);
  const history = useIncidentHistory(incidentId);
  const queryClient = useQueryClient();
  const [pulling, setPulling] = useState(false);

  async function refresh() {
    setPulling(true);
    try {
      // The list too, so going back does not show an older status than this detail.
      await queryClient.invalidateQueries({ queryKey: incidentKeys.all });
    } finally {
      setPulling(false);
    }
  }

  if (incident.isPending) {
    return (
      <View style={[screenStyles.screen, screenStyles.centered]}>
        <ActivityIndicator accessibilityLabel="Loading the report" size="large" />
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
      style={screenStyles.flex}
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={pulling} onRefresh={refresh} />}
    >
      {(incident.isRefetchError || history.isRefetchError) && (
        <FormMessage message="The report could not be refreshed. This is the last version loaded." />
      )}
      <IncidentData incident={incident.data} />
      {/* The API lets the reporter add photos only while the report waits for review (#44). */}
      {incident.data.status === 'REPORTED' && (
        <Button
          label="Add photos"
          variant="secondary"
          onPress={() => navigation.navigate('AddPhotos', { incidentId })}
        />
      )}
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

function IncidentData({ incident }: { incident: Incident }) {
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
      <Section title="Details">
        <Field label="Category" value={incident.category?.name ?? 'Not set yet'} />
        <Field label="Reported on" value={formatInstant(incident.reportedAt)} />
        <Field label="Updated on" value={formatInstant(incident.updatedAt)} />
        {incident.closedAt !== null && (
          <Field label="Closed on" value={formatInstant(incident.closedAt)} />
        )}
      </Section>
      <Section title="Location">
        <Field label="Address" value={location.address ?? NOT_AVAILABLE} />
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

function Field({ label, value }: { label: string; value: string }) {
  return (
    // One element for screen readers, so the label and its value are read together.
    <View style={styles.field} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text style={screenStyles.note}>{label}</Text>
      <Text style={screenStyles.body}>{value}</Text>
    </View>
  );
}

const MARK_SIZE = 10;

const styles = StyleSheet.create({
  content: {
    padding: spacing.md,
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
