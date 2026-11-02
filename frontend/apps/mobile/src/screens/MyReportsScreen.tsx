import { Ionicons } from '@expo/vector-icons';
import type { BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import type { CompositeScreenProps } from '@react-navigation/native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { components } from '@urbanpulse/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { colors, fontSizes, formatInstant, spacing, STATUS_LABELS } from '@urbanpulse/ui';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Platform,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { Button, MIN_TOUCH_SIZE } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { LoadFailure } from '../components/LoadFailure';
import { StatusBadge } from '../components/StatusBadge';
import { useRefreshOnFocus } from '../hooks/useRefreshOnFocus';
import { incidentKeys, useMyReports, type ReportGroup } from '../incidents/incident-queries';
import type { MainTabsParamList } from '../navigation/MainTabs';
import type { MyReportsStackParamList } from '../navigation/MyReportsStack';
import { screenStyles } from './styles';

type Props = CompositeScreenProps<
  NativeStackScreenProps<MyReportsStackParamList, 'ReportList'>,
  BottomTabScreenProps<MainTabsParamList>
>;

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];

const GROUPS: readonly { group: ReportGroup; label: string; empty: string }[] = [
  { group: 'open', label: 'Open', empty: 'You have no open reports.' },
  { group: 'closed', label: 'Closed', empty: 'None of your reports is closed yet.' },
];

/** The citizen's own reports (RF06), open or closed, newest first. */
export function MyReportsScreen({ navigation }: Props) {
  const [group, setGroup] = useState<ReportGroup>('open');
  const reports = useMyReports(group);
  const queryClient = useQueryClient();
  useRefreshOnFocus(reports.refetch);
  // Only a pull shows the refresh spinner; a background refetch must not cover the list.
  const [pulling, setPulling] = useState(false);

  const incidents = useMemo(() => uniqueById(reports.data?.pages), [reports.data]);

  async function refresh() {
    setPulling(true);
    try {
      // Every incident query, so a detail opened next does not show an older status than the list.
      await queryClient.invalidateQueries({ queryKey: incidentKeys.all });
    } finally {
      setPulling(false);
    }
  }

  // A failed page waits for "Try again": reaching the end again must not retry in a loop. While a
  // refetch runs, `fetchNextPage` would cancel it in silence, losing the refreshed rows. Read in
  // render, not in `loadMore`: React Query re-renders only for the properties a render read.
  const canLoadMore = reports.hasNextPage && !reports.isFetching && !reports.isFetchNextPageError;

  function loadMore() {
    if (canLoadMore) {
      void reports.fetchNextPage();
    }
  }

  return (
    <View style={screenStyles.flex}>
      <View style={styles.groups} accessibilityRole="tablist">
        {GROUPS.map(({ group: option, label }) => {
          const selected = option === group;
          return (
            <Pressable
              key={option}
              accessibilityRole="tab"
              accessibilityState={{ selected }}
              onPress={() => setGroup(option)}
              style={[styles.group, selected && styles.groupSelected]}
            >
              <Text style={[styles.groupLabel, selected && styles.groupLabelSelected]}>
                {label}
              </Text>
            </Pressable>
          );
        })}
      </View>
      {reports.isPending ? (
        <View style={[screenStyles.screen, screenStyles.centered]}>
          <ActivityIndicator accessibilityLabel="Loading your reports" size="large" />
        </View>
      ) : reports.isError && incidents.length === 0 ? (
        <View style={screenStyles.screen}>
          <LoadFailure
            what="Your reports"
            error={reports.error}
            retrying={reports.isFetching}
            onRetry={() => void reports.refetch()}
          />
        </View>
      ) : (
        <FlatList
          testID="my-reports-list"
          data={incidents}
          keyExtractor={(incident) => incident.id}
          renderItem={({ item }) => (
            <ReportRow
              incident={item}
              onPress={() => navigation.navigate('ReportDetail', { incidentId: item.id })}
            />
          )}
          ItemSeparatorComponent={Separator}
          contentContainerStyle={incidents.length === 0 ? styles.emptyContainer : undefined}
          ListHeaderComponent={
            reports.isRefetchError ? (
              <View style={styles.banner}>
                <FormMessage message="Your reports could not be refreshed. These are the last ones loaded." />
              </View>
            ) : undefined
          }
          ListEmptyComponent={
            <View style={styles.empty}>
              <Text style={screenStyles.body}>
                {GROUPS.find((option) => option.group === group)?.empty}
              </Text>
              {group === 'open' && (
                <Button
                  label="Report an incident"
                  onPress={() => navigation.navigate('NewReport')}
                />
              )}
            </View>
          }
          ListFooterComponent={
            incidents.length === 0 ? undefined : (
              <ListFooter
                loading={reports.isFetchingNextPage}
                failed={reports.isFetchNextPageError}
                complete={!reports.hasNextPage}
                onRetry={() => void reports.fetchNextPage()}
              />
            )
          }
          onEndReached={loadMore}
          onEndReachedThreshold={0.5}
          refreshControl={<RefreshControl refreshing={pulling} onRefresh={refresh} />}
        />
      )}
    </View>
  );
}

function ReportRow({ incident, onPress }: { incident: IncidentSummary; onPress: () => void }) {
  const reportedAt = formatInstant(incident.reportedAt);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${incident.title}, ${STATUS_LABELS[incident.status]}, reported ${reportedAt}`}
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}
    >
      <View style={styles.rowContent}>
        <Text style={styles.rowTitle} numberOfLines={2}>
          {incident.title}
        </Text>
        <View style={styles.rowMeta}>
          <StatusBadge status={incident.status} />
          <Text style={screenStyles.note}>{reportedAt}</Text>
        </View>
      </View>
      {/* iOS marks a row that opens a detail with a chevron; Android rows go without one. */}
      {Platform.OS === 'ios' && (
        <Ionicons name="chevron-forward" size={20} color={colors.textMuted} />
      )}
    </Pressable>
  );
}

interface ListFooterProps {
  loading: boolean;
  failed: boolean;
  complete: boolean;
  onRetry: () => void;
}

/** What happens at the end of the list: the next page loading, failing, or nothing more. */
function ListFooter({ loading, failed, complete, onRetry }: ListFooterProps) {
  if (loading) {
    return (
      <View style={styles.footer}>
        <ActivityIndicator accessibilityLabel="Loading more reports" />
        <Text style={screenStyles.note}>Loading more…</Text>
      </View>
    );
  }
  if (failed) {
    return (
      <View style={styles.footer}>
        <FormMessage message="More reports could not be loaded." />
        <Button label="Try again" onPress={onRetry} variant="secondary" />
      </View>
    );
  }
  return complete ? (
    <View style={styles.footer}>
      <Text style={screenStyles.note}>That&apos;s all.</Text>
    </View>
  ) : null;
}

function Separator() {
  return <View style={styles.separator} />;
}

/**
 * The rows of every loaded page, once each. Pages are offsets, so a report created while the citizen
 * scrolls pushes the last row of a page onto the next one. The opposite case is a known limit: a
 * report that leaves the group between two pages pulls a row back, which the next page then skips
 * until the citizen refreshes.
 */
function uniqueById(pages: readonly { content: IncidentSummary[] }[] | undefined) {
  const seen = new Set<string>();
  return (pages ?? [])
    .flatMap((page) => page.content)
    .filter((incident) => {
      if (seen.has(incident.id)) {
        return false;
      }
      seen.add(incident.id);
      return true;
    });
}

const styles = StyleSheet.create({
  groups: {
    flexDirection: 'row',
    margin: spacing.md,
    borderRadius: 8,
    backgroundColor: colors.surface,
    padding: spacing.xs,
  },
  group: {
    flex: 1,
    minHeight: MIN_TOUCH_SIZE,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: 6,
  },
  // The border, not only the label, shows which group is selected: white on the surface is 1.1:1.
  groupSelected: {
    backgroundColor: colors.background,
    borderWidth: 1,
    borderColor: colors.textMuted,
  },
  // `textMuted` on `surface` is 4.3:1, under the 4.5:1 that text of this size needs.
  groupLabel: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
  groupLabelSelected: {
    color: colors.text,
    fontWeight: '600',
  },
  banner: {
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.sm,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: MIN_TOUCH_SIZE,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  rowContent: {
    flex: 1,
    gap: spacing.xs,
  },
  rowPressed: {
    backgroundColor: colors.surface,
  },
  rowTitle: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  // Wraps at the largest text sizes instead of pushing the date off the screen.
  rowMeta: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: spacing.xs,
  },
  separator: {
    height: StyleSheet.hairlineWidth,
    marginLeft: spacing.md,
    backgroundColor: colors.textMuted,
  },
  emptyContainer: {
    flexGrow: 1,
  },
  empty: {
    flex: 1,
    justifyContent: 'center',
    padding: spacing.md,
    gap: spacing.md,
  },
  footer: {
    alignItems: 'center',
    padding: spacing.md,
    gap: spacing.sm,
  },
});
