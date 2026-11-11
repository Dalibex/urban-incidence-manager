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
      title: 'By status',
      bars: statistics.byStatus.map(({ status, count }) => ({
        label: STATUS_LABELS[status],
        count,
        color: statusColor(status),
      })),
    },
    {
      title: 'By category',
      bars: statistics.byCategory.map(({ name, count }) => groupBar(name, NOT_CLASSIFIED, count)),
    },
    {
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
  const values = bars.map(({ label, count }) => `${label} ${count}`).join(', ');
  return `Incidents ${title.toLowerCase()}: ${values}.`;
}

/** Room for each bar and for the margins around them, in pixels. */
const BAR_BAND_PX = 20;
const MARGINS_PX = 16;

/** The height of a chart, so its placeholder takes the same room while Plotly loads. */
export function chartHeight(barCount: number): number {
  return MARGINS_PX + barCount * BAR_BAND_PX;
}
