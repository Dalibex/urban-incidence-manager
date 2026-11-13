import type { components, IncidentReportQuery } from '@urbanpulse/api-client';
import { startOfDay } from '@urbanpulse/ui';

import { addDays, isDay } from '../incidents/filters/incident-filters';

export type ReportGrouping = components['schemas']['IncidentReportGrouping'];

/** The order of the *Group by* choices: the same as the contract. */
export const REPORT_GROUPINGS: readonly ReportGrouping[] = [
  'status',
  'category',
  'priority',
  'department',
  'district',
];

export const GROUPING_LABELS: Record<ReportGrouping, string> = {
  status: 'Status',
  category: 'Category',
  priority: 'Priority',
  department: 'Department',
  district: 'District',
};

/**
 * What the analyst asks for: both days included, as `YYYY-MM-DD` days of Málaga, the city the
 * report is about, whatever the zone of the browser.
 */
export interface ReportRequest {
  fromDay: string;
  toDay: string;
  groupBy: ReportGrouping;
}

/** The longest period the API accepts (#42): a leap year. */
const MAX_PERIOD_MS = 366 * 24 * 60 * 60 * 1000;

/** The days shown when the screen opens: the last 30, today included, grouped by status. */
export const DEFAULT_PERIOD_DAYS = 30;

const CITY_DAY = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Madrid',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** The day it is in Málaga at an instant, as `YYYY-MM-DD`. */
export function cityDay(instant: Date): string {
  return CITY_DAY.format(instant);
}

export function defaultRequest(now: Date): ReportRequest {
  const toDay = cityDay(now);
  return { fromDay: addDays(toDay, 1 - DEFAULT_PERIOD_DAYS), toDay, groupBy: 'status' };
}

/** The query of `getIncidentReport`: the first day starts the period, the day after the last ends it. */
export function toReportQuery(request: ReportRequest): IncidentReportQuery {
  return {
    from: startOfDay(request.fromDay),
    to: startOfDay(addDays(request.toDay, 1)),
    groupBy: request.groupBy,
  };
}

/**
 * Why the API would refuse the period with `422`, so the screen says it next to *To* without
 * asking; `undefined` when it is valid. The length is measured on the instants that are sent, as
 * the API does, so a summer time change cannot make them disagree.
 */
export function periodError(request: ReportRequest): string | undefined {
  if (!isDay(request.fromDay) || !isDay(request.toDay)) {
    return 'Choose the first and the last day of the period.';
  }
  if (request.toDay < request.fromDay) {
    return 'The last day must be on or after the first day.';
  }
  const query = toReportQuery(request);
  if (Date.parse(query.to) - Date.parse(query.from) > MAX_PERIOD_MS) {
    return 'The period may not be longer than 366 days.';
  }
  return undefined;
}

/** The name of the downloaded file, so several reports do not overwrite each other. */
export function csvFileName(request: ReportRequest): string {
  return `incident-report-${request.groupBy}-${request.fromDay}-to-${request.toDay}.csv`;
}
