import type { Role } from '@urbanpulse/domain';

import {
  filtersSearch,
  INBOX_DEFAULT_FILTERS,
  MAP_DEFAULT_FILTERS,
  WORK_QUEUE_DEFAULT_FILTERS,
} from '../incidents/filters/incident-filters';
import { rememberedPath } from '../incidents/filters/remembered-filters';

export const PATHS = {
  home: '/',
  signIn: '/sign-in',
  inbox: '/inbox',
  incident: '/incidents/:incidentId',
  workQueue: '/work-queue',
  map: '/map',
  users: '/users',
  auditLog: '/audit-log',
  reports: '/reports',
  useMobile: '/use-mobile',
} as const;

/**
 * Roles that manage every incident: the inbox and the incident detail. ADR-0020 calls them
 * "operator", and the permission matrix (docs/security.md) lets both read every incident.
 */
export const OPERATOR_ROLES: readonly Role[] = ['MUNICIPAL_OPERATOR', 'ADMINISTRATOR'];

/**
 * Roles that open the detail of an incident: operators, and the technician, who only gets the
 * incidents actively assigned to them (the API answers `404` to any other, docs/security.md).
 */
export const INCIDENT_DETAIL_ROLES: readonly Role[] = [...OPERATOR_ROLES, 'TECHNICIAN'];

/** Where the menu and the sign-in open the inbox: on the reports waiting for a review (#183). */
export const INBOX_DEFAULT_PATH = PATHS.inbox + filtersSearch(INBOX_DEFAULT_FILTERS);

/** Where the menu and the sign-in open the work queue: on the work still to do (#187, #217). */
export const WORK_QUEUE_DEFAULT_PATH = PATHS.workQueue + filtersSearch(WORK_QUEUE_DEFAULT_FILTERS);

/**
 * Where the menu opens the map (#196): an operator on the live incidents, a technician on the same
 * pending work as their queue.
 */
export function mapDefaultPath(role: Role): string {
  const filters = role === 'TECHNICIAN' ? WORK_QUEUE_DEFAULT_FILTERS : MAP_DEFAULT_FILTERS;
  return PATHS.map + filtersSearch(filters);
}

/** The list of incidents a role works from: the inbox, or the technician's work queue. */
export type IncidentList = 'inbox' | 'workQueue';

/** A screen that opens the detail of an incident, and that the detail leads back to. */
export type DetailOrigin = IncidentList;

const DETAIL_ORIGINS: Record<DetailOrigin, { path: string; roles: readonly Role[] }> = {
  inbox: { path: PATHS.inbox, roles: OPERATOR_ROLES },
  workQueue: { path: PATHS.workQueue, roles: ['TECHNICIAN'] },
};

const LIST_DEFAULT_PATHS: Record<IncidentList, string> = {
  inbox: INBOX_DEFAULT_PATH,
  workQueue: WORK_QUEUE_DEFAULT_PATH,
};

/** The list the detail of an incident leads back to for a role. */
export function incidentListOf(role: Role | undefined): IncidentList {
  return role === 'TECHNICIAN' ? 'workQueue' : 'inbox';
}

/** Navigation state of a link to the detail, so the detail can lead back to the same filters. */
export interface DetailReturn {
  from: DetailOrigin;
  /** The query string of that screen, `''` or starting with `?`. */
  search: string;
}

/** The screen the detail leads back to, and its address. */
export interface DetailBack {
  to: DetailOrigin;
  path: string;
}

function isDetailReturn(state: unknown): state is DetailReturn {
  if (typeof state !== 'object' || state === null) {
    return false;
  }
  const { from, search } = state as Partial<DetailReturn>;
  return (
    typeof from === 'string' &&
    Object.hasOwn(DETAIL_ORIGINS, from) &&
    typeof search === 'string' &&
    (search === '' || search.startsWith('?'))
  );
}

/**
 * Where the detail of an incident leads back to: the screen that opened it, as the user left it,
 * if the role can open that screen. A detail opened directly (a shared link, a reload in a new tab)
 * leads to the default list of the role.
 */
export function detailBack(state: unknown, role: Role | undefined): DetailBack {
  if (isDetailReturn(state) && role && DETAIL_ORIGINS[state.from].roles.includes(role)) {
    return { to: state.from, path: DETAIL_ORIGINS[state.from].path + state.search };
  }
  const list = incidentListOf(role);
  return { to: list, path: LIST_DEFAULT_PATHS[list] };
}

/** Navigation state of the sign-in, so it can lead back to the screen that asked for it. */
export interface SignInReturn {
  returnTo: string;
  /** Who was signed in when the sign-in was asked for, if anyone: only they return to `returnTo`. */
  userId?: string;
}

/**
 * Where the sign-in goes once there is a session: the screen that sent the user to it, with its
 * address (a reload or a link to a filtered inbox, #78 T7), or `/`, which leads to their own screen.
 * Another user than the one who left goes to their own screen: the address holds the filters of the
 * first one (#263).
 */
export function signInReturnPath(state: unknown, userId: string): string {
  if (typeof state !== 'object' || state === null) {
    return PATHS.home;
  }
  const { returnTo, userId: leftBy } = state as Partial<SignInReturn>;
  const sameUser = leftBy === undefined || leftBy === userId;
  return typeof returnTo === 'string' && sameUser ? returnTo : PATHS.home;
}

/** Path of the detail page of one incident. */
export function incidentPath(incidentId: string): string {
  return PATHS.incident.replace(':incidentId', encodeURIComponent(incidentId));
}

/** Screen each role lands on after signing in. Citizens use the mobile app, so they get a notice. */
const LANDING_PATHS: Record<Role, string> = {
  MUNICIPAL_OPERATOR: INBOX_DEFAULT_PATH,
  TECHNICIAN: WORK_QUEUE_DEFAULT_PATH,
  ADMINISTRATOR: PATHS.users,
  ANALYST: PATHS.reports,
  CITIZEN: PATHS.useMobile,
};

/**
 * Where a role lands. With the user's id, an operator or a technician lands on the filters they
 * last used on their list, the defaults only the first time (#263).
 */
export function landingPath(role: Role, userId?: string): string {
  const path = LANDING_PATHS[role];
  if (!userId) {
    return path;
  }
  if (role === 'MUNICIPAL_OPERATOR') {
    return rememberedPath(PATHS.inbox, 'inbox', userId, path);
  }
  return role === 'TECHNICIAN' ? rememberedPath(PATHS.workQueue, 'workQueue', userId, path) : path;
}

export type RouteAccess = 'allowed' | 'sign-in' | 'forbidden';

/**
 * Decides whether the current user may open a screen. This is convenience for the interface only:
 * the API checks the role of every request again.
 */
export function routeAccess(role: Role | null, allowedRoles: readonly Role[]): RouteAccess {
  if (role === null) {
    return 'sign-in';
  }
  return allowedRoles.includes(role) ? 'allowed' : 'forbidden';
}
