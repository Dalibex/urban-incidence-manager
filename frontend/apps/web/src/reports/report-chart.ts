import type { components } from '@urbanpulse/api-client';

import { groupLabel } from './report-groups';
import type { ReportGrouping } from './report-period';

type ReportRow = components['schemas']['IncidentReportRow'];

/** The parts of a group's total, in the order the bar stacks them: they add up to `total`. */
export const REPORT_PARTS = ['open', 'closed', 'rejected'] as const;

export type ReportPart = (typeof REPORT_PARTS)[number];

export const PART_LABELS: Record<ReportPart, string> = {
  open: 'Open',
  closed: 'Closed',
  rejected: 'Rejected',
};

export interface ReportChartSeries {
  part: ReportPart;
  label: string;
  /** One count per group, in the order of `groups`. */
  counts: number[];
}

export interface ReportChartData {
  /** The groups as the table names them, in the order of the report. */
  groups: string[];
  series: ReportChartSeries[];
}

/** The rows of a report as one stacked bar per group, open, closed and rejected. */
export function reportChartData(groupBy: ReportGrouping, rows: ReportRow[]): ReportChartData {
  return {
    groups: rows.map((row) => groupLabel(groupBy, row.group)),
    series: REPORT_PARTS.map((part) => ({
      part,
      label: PART_LABELS[part],
      counts: rows.map((row) => row[part]),
    })),
  };
}

/** What the screen says when the chart cannot load or draw: the table keeps every figure. */
export const CHART_UNAVAILABLE =
  'The chart is not available. The table below has the same figures.';

/** Room for each bar, and for the legend above and the axis below them, in pixels. */
const GROUP_BAND_PX = 36;
const LEGEND_AND_AXIS_PX = 104;

/** The height of the chart of a report, so its placeholder takes the same room while it loads. */
export function chartHeight(groupCount: number): number {
  return LEGEND_AND_AXIS_PX + groupCount * GROUP_BAND_PX;
}
