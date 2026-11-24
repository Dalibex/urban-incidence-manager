import type { components, IncidentListQuery } from '@urbanpulse/api-client';
import {
  INCIDENT_STATUSES,
  isIncidentStatus,
  OPEN_STATUSES,
  type IncidentStatus,
} from '@urbanpulse/domain';
import { startOfDay } from '@urbanpulse/ui';

type Priority = components['schemas']['Priority'];

/**
 * The staff filters of an incident list (#58), as the screens keep them in the address. Several
 * statuses (#217), categories and departments (#265), one value for each other filter; a missing
 * one does not filter. The dates are calendar days in Málaga, both included, which is how people
 * read "from 1 to 5 November".
 */
export interface IncidentFilters {
  /** Any of these statuses, in the order of the lifecycle and never empty. */
  status?: readonly IncidentStatus[];
  /** Any of these categories, in string order and never empty. */
  categoryId?: readonly string[];
  priority?: Priority;
  /** Any of these departments, in string order and never empty. */
  departmentId?: readonly string[];
  /** First day, `YYYY-MM-DD`, included. */
  reportedFrom?: string;
  /** Last day, `YYYY-MM-DD`, included. */
  reportedTo?: string;
  /**
   * Text in the title or the description, without the spaces around it (#266). Only the map
   * searches: it is not one of the list filters below, so the inbox and the work queue ignore a
   * `q` in their address, and the filters a list remembers never hold it.
   */
  q?: string;
}

type IncidentFilterName = keyof IncidentFilters;

/**
 * Every list filter, in the order the filter bar shows them; also the names of the URL parameters.
 * The text search is apart: see `searchFrom` and `withSearch`.
 */
const FILTER_NAMES: readonly Exclude<IncidentFilterName, 'q'>[] = [
  'status',
  'categoryId',
  'priority',
  'departmentId',
  'reportedFrom',
  'reportedTo',
];

export const PRIORITIES: readonly Priority[] = ['HIGH', 'MEDIUM', 'LOW'];

/** What the inbox shows when it is opened from the menu: the reports waiting for a review. */
export const INBOX_DEFAULT_FILTERS: IncidentFilters = { status: ['REPORTED'] };

/**
 * What the work queue shows when it is opened from the menu: the technician's pending work. An
 * assignment stays active after the incident is resolved or closed, so without a filter the queue
 * would mostly list finished work (#217).
 */
export const WORK_QUEUE_DEFAULT_FILTERS: IncidentFilters = { status: ['ASSIGNED', 'IN_PROGRESS'] };

/**
 * What the map shows an operator when it is opened from the menu: the live incidents, every status
 * that can still change (#196). A technician opens it on their pending work instead, the same as
 * their queue.
 */
export const MAP_DEFAULT_FILTERS: IncidentFilters = { status: OPEN_STATUSES };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// A four-digit year from 1000: `Date.UTC` reads the years 0 to 99 as 1900 to 1999.
const DAY = /^[1-9]\d{3}-\d{2}-\d{2}$/;
// The last day of year 9999 has no next day in this format to end a range on.
const LAST_DAY = '9999-12-31';

// The limits of the text search `q` in the contract (`TextSearch`), in characters once trimmed.
const SEARCH_MIN_LENGTH = 3;
const SEARCH_MAX_LENGTH = 100;

/**
 * The filters of an address. A value the API would reject (an unknown status, an id that is not a
 * UUID, a day that does not exist, a first day after the last one) is dropped, never sent: the list
 * then shows more incidents, never an error. A repeated status, category or department counts
 * once; any other repeated parameter keeps its first valid value.
 */
export function filtersFrom(params: URLSearchParams): IncidentFilters {
  const filters: IncidentFilters = {
    status: statusesFrom(params.getAll('status').filter(isIncidentStatus)),
    categoryId: idsFrom(params.getAll('categoryId').filter(isUuid)),
    priority: first(params, 'priority', isPriority),
    departmentId: idsFrom(params.getAll('departmentId').filter(isUuid)),
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
    // The statuses, categories and departments repeat the parameter, as the API reads them
    // (`?status=A&status=B`).
    const values = typeof value === 'string' ? [value] : (value ?? []);
    for (const each of values) {
      if (each) {
        next.append(name, each);
      }
    }
  }
  return next;
}

/**
 * The text search of the map in an address (#266), trimmed; `undefined` without one, or when the API
 * would reject it, so it is dropped, never sent.
 */
export function searchFrom(params: URLSearchParams): string | undefined {
  return params
    .getAll('q')
    .map((text) => text.trim())
    .find((text) => searchTextProblem(text) === undefined);
}

/** The address of a search: `params` with `q` replaced by `text`, or without it. */
export function withSearch(params: URLSearchParams, text: string | undefined): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete('q');
  if (text) {
    next.set('q', text);
  }
  return next;
}

/**
 * The statuses to filter by, each once and in the order of the lifecycle, so the same choice always
 * gives the same address; none means no status filter.
 */
export function statusesFrom(
  statuses: readonly IncidentStatus[],
): readonly IncidentStatus[] | undefined {
  const chosen = INCIDENT_STATUSES.filter((status) => statuses.includes(status));
  return chosen.length > 0 ? chosen : undefined;
}

/**
 * The ids to filter by, each once, in lower case and in string order, so the same choice always
 * gives the same address; none means no filter.
 */
export function idsFrom(ids: readonly string[]): readonly string[] | undefined {
  const chosen = [...new Set(ids.map((id) => id.toLowerCase()))].sort();
  return chosen.length > 0 ? chosen : undefined;
}

/**
 * What the screens call `count` chosen ids that are not among the options (an inactive category, an
 * old link): one is *Unknown category*; several are numbered in the order of their ids, so a screen
 * reader can tell apart the one it unticks.
 */
export function unknownNames(count: number, noun: string): string[] {
  if (count === 1) {
    return [`Unknown ${noun}`];
  }
  return Array.from({ length: count }, (_, index) => `Unknown ${noun} ${index + 1}`);
}

/** The address query (`?status=REPORTED`) of some filters, for a link. */
export function filtersSearch(filters: IncidentFilters): string {
  const query = withFilters(new URLSearchParams(), filters).toString();
  return query ? `?${query}` : '';
}

/** The filters as the query of `listIncidents`; the days become the instants they start at. */
export function toListQuery(filters: IncidentFilters): IncidentListQuery {
  const { status, categoryId, priority, departmentId, reportedFrom, reportedTo, q } = filters;
  return {
    ...(status && { status: [...status] }),
    ...(categoryId && { categoryId: [...categoryId] }),
    ...(priority && { priority: [priority] }),
    ...(departmentId && { departmentId: [...departmentId] }),
    ...(reportedFrom && { reportedFrom: startOfDay(reportedFrom) }),
    // The API excludes `reportedTo`, so the last day ends where the next one starts.
    ...(reportedTo && { reportedTo: startOfDay(nextDay(reportedTo)) }),
    ...(q && { q }),
  };
}

export function hasFilters(filters: IncidentFilters): boolean {
  return filters.q !== undefined || FILTER_NAMES.some((name) => filters[name] !== undefined);
}

/**
 * Why the API would reject `text` as a search, as the box says it; `undefined` when it would not.
 * Counted in characters, as the contract does, so an emoji counts as one.
 */
export function searchTextProblem(text: string): string | undefined {
  const length = [...text.trim()].length;
  if (length < SEARCH_MIN_LENGTH) {
    return `Type at least ${SEARCH_MIN_LENGTH} characters.`;
  }
  if (length > SEARCH_MAX_LENGTH) {
    return `Type at most ${SEARCH_MAX_LENGTH} characters.`;
  }
  // The API refuses it, so an address with `%00` would fail on every Try again.
  if (text.includes('\u0000')) {
    return 'Remove the null character.';
  }
  return undefined;
}

function first<T extends string>(
  params: URLSearchParams,
  name: IncidentFilterName,
  isValid: (value: string) => value is T,
): T | undefined {
  return params.getAll(name).find(isValid);
}

/** The filters without the ones left empty, so `{ status: [] }` reads as no filter. */
export function withoutEmpty(filters: IncidentFilters): IncidentFilters {
  return Object.fromEntries(
    Object.entries(filters).filter(
      ([, value]) =>
        value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0),
    ),
  ) as IncidentFilters;
}

function isUuid(value: string): value is string {
  return UUID.test(value);
}

export function isPriority(value: string): value is Priority {
  return (PRIORITIES as readonly string[]).includes(value);
}

/** A real calendar day as `YYYY-MM-DD`: `2026-02-30` is not one. */
export function isDay(value: string): value is string {
  if (!DAY.test(value) || value >= LAST_DAY) {
    return false;
  }
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year!, month! - 1, day!));
  return date.getUTCMonth() === month! - 1 && date.getUTCDate() === day;
}

/** The day after `day`, both `YYYY-MM-DD`. */
export function nextDay(day: string): string {
  return addDays(day, 1);
}

/** `day` moved by `days` (negative goes back), both `YYYY-MM-DD`. */
export function addDays(day: string, days: number): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(Date.UTC(year!, month! - 1, date! + days)).toISOString().slice(0, 10);
}

/** A quick choice of the days reported: the last `days` days, today included (#177). */
export interface RangeShortcut {
  label: string;
  days: number;
}

export const RANGE_SHORTCUTS: readonly RangeShortcut[] = [
  { label: 'Today', days: 1 },
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 30 days', days: 30 },
];

/** The days of a shortcut, ending on `today` (a day in Málaga, `YYYY-MM-DD`). */
export function shortcutRange(
  shortcut: RangeShortcut,
  today: string,
): Pick<IncidentFilters, 'reportedFrom' | 'reportedTo'> {
  return { reportedFrom: addDays(today, 1 - shortcut.days), reportedTo: today };
}
