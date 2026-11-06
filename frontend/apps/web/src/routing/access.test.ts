import { ROLES } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { PATHS, inboxReturnPath, landingPath, routeAccess, signInReturnPath } from './access';

describe('routeAccess', () => {
  it('sends a visitor without a session to sign in', () => {
    expect(routeAccess(null, ROLES)).toBe('sign-in');
  });

  it('allows a role listed for the screen', () => {
    expect(routeAccess('TECHNICIAN', ['MUNICIPAL_OPERATOR', 'TECHNICIAN'])).toBe('allowed');
  });

  it('forbids a role not listed for the screen', () => {
    expect(routeAccess('CITIZEN', ['MUNICIPAL_OPERATOR'])).toBe('forbidden');
  });

  it('forbids every role when the screen lists none', () => {
    for (const role of ROLES) {
      expect(routeAccess(role, [])).toBe('forbidden');
    }
  });
});

describe('landingPath', () => {
  it('lands each role on its own screen, the operator on the reports waiting for a review', () => {
    expect(landingPath('MUNICIPAL_OPERATOR')).toBe(`${PATHS.inbox}?status=REPORTED`);
    expect(landingPath('TECHNICIAN')).toBe(PATHS.workQueue);
    expect(landingPath('ADMINISTRATOR')).toBe(PATHS.users);
    expect(landingPath('ANALYST')).toBe(PATHS.reports);
    expect(landingPath('CITIZEN')).toBe(PATHS.useMobile);
  });
});

describe('inboxReturnPath', () => {
  it('goes back to the inbox the user came from', () => {
    expect(inboxReturnPath({ inboxSearch: '?priority=HIGH&page=2' })).toBe(
      `${PATHS.inbox}?priority=HIGH&page=2`,
    );
    expect(inboxReturnPath({ inboxSearch: '' })).toBe(PATHS.inbox);
  });

  it('goes to the default inbox after a direct link', () => {
    expect(inboxReturnPath(null)).toBe(`${PATHS.inbox}?status=REPORTED`);
    expect(inboxReturnPath({ inboxSearch: 42 })).toBe(`${PATHS.inbox}?status=REPORTED`);
  });
});

describe('signInReturnPath', () => {
  it('goes back to the screen that asked for the sign-in, with its address', () => {
    expect(signInReturnPath({ returnTo: `${PATHS.inbox}?priority=HIGH` })).toBe(
      `${PATHS.inbox}?priority=HIGH`,
    );
  });

  it("goes to the user's own screen when the sign-in was opened directly", () => {
    expect(signInReturnPath(null)).toBe(PATHS.home);
    expect(signInReturnPath({ returnTo: 42 })).toBe(PATHS.home);
  });
});
