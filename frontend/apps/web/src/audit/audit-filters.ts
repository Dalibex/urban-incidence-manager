import type { AuditEntryListQuery } from '@urbanpulse/api-client';
import { startOfDay } from '@urbanpulse/ui';

import { isDay, nextDay } from '../incidents/filters/incident-filters';
import { AUDIT_ACTIONS, isAuditAction, type AuditAction } from './audit-labels';

/**
 * The filters of the audit log (#41), as the screen keeps them in the address. The days are
 * calendar days in Málaga, both included, like the incident lists. The actor is kept by id only:
 * a name or an email in the address would be personal data in every link and history entry.
 */
export interface AuditFilters {
  /** Any of these actions, in the order of `AUDIT_ACTIONS` and never empty. */
  action?: readonly AuditAction[];
  actorId?: string;
  /** First day, `YYYY-MM-DD`, included. */
  from?: string;
  /** Last day, `YYYY-MM-DD`, included. */
  to?: string;
}

type AuditFilterName = keyof AuditFilters;

/** Every filter; also the names of the URL parameters. */
const FILTER_NAMES: readonly AuditFilterName[] = ['action', 'actorId', 'from', 'to'];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The filters of an address. A value the API would reject (an unknown action, an id that is not a
 * UUID, a day that does not exist, a first day after the last one) is dropped, never sent: the log
 * then shows more entries, never an error.
 */
export function filtersFrom(params: URLSearchParams): AuditFilters {
  const filters: AuditFilters = {
    action: actionsFrom(params.getAll('action').filter(isAuditAction)),
    actorId: params.getAll('actorId').find((value) => UUID.test(value)),
    from: params.getAll('from').find(isDay),
    to: params.getAll('to').find(isDay),
  };
  if (filters.from && filters.to && filters.from > filters.to) {
    // Which of the two is wrong cannot be told, so neither is applied.
    delete filters.from;
    delete filters.to;
  }
  return withoutEmpty(filters);
}

/** The address of the filters: `params` with its filter parameters replaced by `filters`. */
export function withFilters(params: URLSearchParams, filters: AuditFilters): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const name of FILTER_NAMES) {
    next.delete(name);
    const value = filters[name];
    const values = typeof value === 'string' ? [value] : (value ?? []);
    for (const each of values) {
      if (each) {
        next.append(name, each);
      }
    }
  }
  return next;
}

/** The actions to filter by, each once and in a fixed order, so the same choice gives one address. */
export function actionsFrom(actions: readonly AuditAction[]): readonly AuditAction[] | undefined {
  const chosen = AUDIT_ACTIONS.filter((action) => actions.includes(action));
  return chosen.length > 0 ? chosen : undefined;
}

/** The filters as the query of `listAuditEntries`; the days become the instants they start at. */
export function toListQuery(filters: AuditFilters): AuditEntryListQuery {
  const { action, actorId, from, to } = filters;
  return {
    ...(action && { action: [...action] }),
    ...(actorId && { actorId }),
    ...(from && { from: startOfDay(from) }),
    // The API excludes `to`, so the last day ends where the next one starts.
    ...(to && { to: startOfDay(nextDay(to)) }),
  };
}

export function hasFilters(filters: AuditFilters): boolean {
  return FILTER_NAMES.some((name) => filters[name] !== undefined);
}

/** The filters without the ones left empty, so `{ action: [] }` reads as no filter. */
export function withoutEmpty(filters: AuditFilters): AuditFilters {
  return Object.fromEntries(
    Object.entries(filters).filter(
      ([, value]) =>
        value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0),
    ),
  ) as AuditFilters;
}
