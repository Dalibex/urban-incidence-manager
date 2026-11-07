import type { Role } from '@urbanpulse/domain';

import {
  filtersSearch,
  INBOX_DEFAULT_FILTERS,
  WORK_QUEUE_DEFAULT_FILTERS,
} from '../incidents/filters/incident-filters';

export const PATHS = {
  home: '/',
  signIn: '/sign-in',
  inbox: '/inbox',
  incident: '/incidents/:incidentId',
  workQueue: '/work-queue',
  users: '/users',
  reports: '/reports',
  useMobile: '/use-mobile',
} as const;

/**
 * Roles that manage every incident: the inbox and the incident detail. ADR-0020 calls them
 * "operator", and the permission matrix (docs/security.md) lets both read every incident.
 */
export const OPERATOR_ROLES: readonly Role[] = ['MUNICIPAL_OPERATOR', 'ADMINISTRATOR'];

/** Where the menu and the sign-in open the inbox: on the reports waiting for a review (#183). */
export const INBOX_DEFAULT_PATH = PATHS.inbox + filtersSearch(INBOX_DEFAULT_FILTERS);

/** Where the menu and the sign-in open the work queue: on the work still to do (#187, #217). */
export const WORK_QUEUE_DEFAULT_PATH = PATHS.workQueue + filtersSearch(WORK_QUEUE_DEFAULT_FILTERS);

/** Navigation state of a link from the inbox, so a screen can lead back to the same filters. */
export interface InboxReturn {
  inboxSearch: string;
}

/** The inbox to go back to: the one the user came from, or the default one after a direct link. */
export function inboxReturnPath(state: unknown): string {
  const search =
    typeof state === 'object' && state !== null && 'inboxSearch' in state
      ? state.inboxSearch
      : undefined;
  return typeof search === 'string' ? PATHS.inbox + search : INBOX_DEFAULT_PATH;
}

/** Navigation state of the sign-in, so it can lead back to the screen that asked for it. */
export interface SignInReturn {
  returnTo: string;
}

/**
 * Where the sign-in goes once there is a session: the screen that sent the user to it, with its
 * address (a reload or a link to a filtered inbox, #78 T7), or `/`, which leads to their own screen.
 */
export function signInReturnPath(state: unknown): string {
  const returnTo =
    typeof state === 'object' && state !== null && 'returnTo' in state ? state.returnTo : undefined;
  return typeof returnTo === 'string' ? returnTo : PATHS.home;
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

export function landingPath(role: Role): string {
  return LANDING_PATHS[role];
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
