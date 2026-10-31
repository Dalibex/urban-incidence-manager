import type { Role } from '@urbanpulse/domain';

export const PATHS = {
  home: '/',
  signIn: '/sign-in',
  inbox: '/inbox',
  workQueue: '/work-queue',
  users: '/users',
  reports: '/reports',
  useMobile: '/use-mobile',
} as const;

/** Screen each role lands on after signing in. Citizens use the mobile app, so they get a notice. */
const LANDING_PATHS: Record<Role, string> = {
  MUNICIPAL_OPERATOR: PATHS.inbox,
  TECHNICIAN: PATHS.workQueue,
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
