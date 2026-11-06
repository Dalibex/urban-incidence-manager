import type { components, IncidentListQuery } from '@urbanpulse/api-client';
import { isIncidentStatus, type IncidentStatus } from '@urbanpulse/domain';
import { startOfDay } from '@urbanpulse/ui';

type Priority = components['schemas']['Priority'];

/**
 * The staff filters of an incident list (#58), as the screens keep them in the address. One value
 * per filter; a missing one does not filter. The dates are calendar days in Málaga, both included,
 * which is how people read "from 1 to 5 November".
 */
export interface IncidentFilters {
  status?: IncidentStatus;
  categoryId?: string;
  priority?: Priority;
  departmentId?: string;
  /** First day, `YYYY-MM-DD`, included. */
  reportedFrom?: string;
  /** Last day, `YYYY-MM-DD`, included. */
  reportedTo?: string;
}

type IncidentFilterName = keyof IncidentFilters;

/** Every filter, in the order the filter bar shows them; also the names of the URL parameters. */
const FILTER_NAMES: readonly IncidentFilterName[] = [
  'status',
  'categoryId',
  'priority',
  'departmentId',
  'reportedFrom',
  'reportedTo',
];

export const PRIORITIES: readonly Priority[] = ['HIGH', 'MEDIUM', 'LOW'];

/** What the inbox shows when it is opened from the menu: the reports waiting for a review. */
export const INBOX_DEFAULT_FILTERS: IncidentFilters = { status: 'REPORTED' };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// A four-digit year from 1000: `Date.UTC` reads the years 0 to 99 as 1900 to 1999.
const DAY = /^[1-9]\d{3}-\d{2}-\d{2}$/;
// The last day of year 9999 has no next day in this format to end a range on.
const LAST_DAY = '9999-12-31';

/**
 * The filters of an address. A value the API would reject (an unknown status, an id that is not a
 * UUID, a day that does not exist, a first day after the last one) is dropped, never sent: the list
 * then shows more incidents, never an error. A repeated parameter keeps its first valid value.
 */
export function filtersFrom(params: URLSearchParams): IncidentFilters {
  const filters: IncidentFilters = {
    status: first(params, 'status', isIncidentStatus),
    categoryId: first(params, 'categoryId', isUuid),
    priority: first(params, 'priority', isPriority),
    departmentId: first(params, 'departmentId', isUuid),
    reportedFrom: first(params, 'reportedFrom', isDay),
    reportedTo: first(params, 'reportedTo', isDay),
  };
  if (filters.reportedFrom && filters.reportedTo && filters.reportedFrom > filters.reportedTo) {
    // Which of the two is wrong cannot be told, so neither is applied.
    delete filters.reportedFrom;
    delete filters.reportedTo;
  }
  return withoutEmpty(filters);
}

/** The address of the filters: `params` with its filter parameters replaced by `filters`. */
export function withFilters(params: URLSearchParams, filters: IncidentFilters): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const name of FILTER_NAMES) {
    next.delete(name);
    const value = filters[name];
    if (value) {
      next.set(name, value);
    }
  }
  return next;
}

/** The address query (`?status=REPORTED`) of some filters, for a link. */
export function filtersSearch(filters: IncidentFilters): string {
  const query = withFilters(new URLSearchParams(), filters).toString();
  return query ? `?${query}` : '';
}

/** The filters as the query of `listIncidents`; the days become the instants they start at. */
export function toListQuery(filters: IncidentFilters): IncidentListQuery {
  const { status, categoryId, priority, departmentId, reportedFrom, reportedTo } = filters;
  return {
    ...(status && { status: [status] }),
    ...(categoryId && { categoryId: [categoryId] }),
    ...(priority && { priority: [priority] }),
    ...(departmentId && { departmentId: [departmentId] }),
    ...(reportedFrom && { reportedFrom: startOfDay(reportedFrom) }),
    // The API excludes `reportedTo`, so the last day ends where the next one starts.
    ...(reportedTo && { reportedTo: startOfDay(nextDay(reportedTo)) }),
  };
}

export function hasFilters(filters: IncidentFilters): boolean {
  return FILTER_NAMES.some((name) => filters[name] !== undefined);
}

function first<T extends string>(
  params: URLSearchParams,
  name: IncidentFilterName,
  isValid: (value: string) => value is T,
): T | undefined {
  return params.getAll(name).find(isValid);
}

/** The filters without the ones left empty, so `{ status: undefined }` reads as no filter. */
export function withoutEmpty(filters: IncidentFilters): IncidentFilters {
  return Object.fromEntries(
    Object.entries(filters).filter(([, value]) => value !== undefined && value !== ''),
  ) as IncidentFilters;
}

function isUuid(value: string): value is string {
  return UUID.test(value);
}

export function isPriority(value: string): value is Priority {
  return (PRIORITIES as readonly string[]).includes(value);
}

/** A real calendar day as `YYYY-MM-DD`: `2026-02-30` is not one. */
function isDay(value: string): value is string {
  if (!DAY.test(value) || value >= LAST_DAY) {
    return false;
  }
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year!, month! - 1, day!));
  return date.getUTCMonth() === month! - 1 && date.getUTCDate() === day;
}

function nextDay(day: string): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(Date.UTC(year!, month! - 1, date! + 1)).toISOString().slice(0, 10);
}
