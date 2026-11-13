import type { components } from '@urbanpulse/api-client';
import { colors, STATUS_LABELS, statusColor } from '@urbanpulse/ui';

import { PRIORITY_LABELS } from '../incidents/labels';

type Statistics = components['schemas']['IncidentStatisticsResponse'];
type Priority = components['schemas']['Priority'];

// What a group without a value is called. These are facts about the incidents (nobody set a
// priority, a category or an assignment yet), not unknown data, so they are counted like any group.
export const NO_PRIORITY = 'No priority';
export const NOT_CLASSIFIED = 'Not classified';
export const NOT_ASSIGNED = 'Not assigned';

/** One bar of a dashboard chart. */
export interface ChartBar {
  label: string;
  count: number;
  color: string;
}

/** A chart of the dashboard: its title, which also names it for assistive technologies. */
export interface DashboardChart {
  /** What it groups by; also the panel the user can fold. */
  id: 'status' | 'category' | 'department';
  title: string;
  bars: ChartBar[];
}

/** One figure of the row under the total, the priorities. */
export interface PriorityFigure {
  priority: Priority | null;
  label: string;
  count: number;
}

// A category or a department is not a status: one hue for all of them, as one ranked series, and
// the group without a value in gray, so it never reads as one more category.
const GROUP_COLOR = colors.primary;
const NO_VALUE_COLOR = colors.textMuted;

/** The priorities the API counted, in its order: `HIGH` to `LOW`, then without priority. */
export function priorityFigures(statistics: Statistics): PriorityFigure[] {
  return statistics.byPriority.map(({ priority, count }) => ({
    priority,
    label: priority ? PRIORITY_LABELS[priority] : NO_PRIORITY,
    count,
  }));
}

/**
 * The three charts, in the order the dashboard shows them. The status bars wear the colour of the
 * markers, so the chart also reads as the colour key of the map.
 */
export function dashboardCharts(statistics: Statistics): DashboardChart[] {
  return [
    {
      id: 'status',
      title: 'By status',
      bars: statistics.byStatus.map(({ status, count }) => ({
        label: STATUS_LABELS[status],
        count,
        color: statusColor(status),
      })),
    },
    {
      id: 'category',
      title: 'By category',
      bars: statistics.byCategory.map(({ name, count }) => groupBar(name, NOT_CLASSIFIED, count)),
    },
    {
      id: 'department',
      title: 'By department',
      bars: statistics.byDepartment.map(({ name, count }) => groupBar(name, NOT_ASSIGNED, count)),
    },
  ];
}

function groupBar(name: string | null, noValue: string, count: number): ChartBar {
  return name === null
    ? { label: noValue, count, color: NO_VALUE_COLOR }
    : { label: name, count, color: GROUP_COLOR };
}

/** The chart in words, for a screen reader: "Incidents by status: Reported 3, Validated 0." */
export function chartDescription({ title, bars }: DashboardChart): string {
  const values = bars.map(({ label, count }) => `${label} ${formatCount(count).full}`).join(', ');
  return `Incidents ${title.toLowerCase()}: ${values}.`;
}

/** Room for each bar and for the margins around them, in pixels. */
const BAR_BAND_PX = 20;
const MARGINS_PX = 16;

/** The height of a chart, so its placeholder takes the same room while Plotly loads. */
export function chartHeight(barCount: number): number {
  return MARGINS_PX + barCount * BAR_BAND_PX;
}

/** From this count on, a figure is written short (`12.3K`); below it, every digit counts. */
export const COMPACT_FROM = 10_000;

const FULL = new Intl.NumberFormat('en');
const COMPACT = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

/** A count as the dashboard writes it, and in full for a tooltip and a screen reader. */
export interface FormattedCount {
  short: string;
  full: string;
}

/**
 * A count that fits its column: `3,000` as it is, `12,345` as `12.3K`, so a large number never
 * spills out of its place. The full number goes with it, so nothing is lost.
 */
export function formatCount(count: number): FormattedCount {
  const full = FULL.format(count);
  return { short: count >= COMPACT_FROM ? COMPACT.format(count) : full, full };
}

const RELATIVE = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * How long ago an incident was reported, in the largest unit that fits: "5 minutes ago",
 * "30 hours ago", "3 days ago". An instant in the future (a clock out of step) reads "just now".
 */
export function reportedAgo(reportedAt: string, now: Date): string {
  const elapsed = Math.max(0, now.getTime() - new Date(reportedAt).getTime());
  // `Intl` says "this minute", which reads oddly in a list.
  if (elapsed < MINUTE_MS) {
    return 'just now';
  }
  if (elapsed < HOUR_MS) {
    return RELATIVE.format(-Math.floor(elapsed / MINUTE_MS), 'minute');
  }
  // In hours up to two days: "yesterday" would read as a calendar day, which 30 hours may not be.
  if (elapsed < 2 * DAY_MS) {
    return RELATIVE.format(-Math.floor(elapsed / HOUR_MS), 'hour');
  }
  return RELATIVE.format(-Math.floor(elapsed / DAY_MS), 'day');
}
