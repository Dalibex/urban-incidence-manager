import { ROLES } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import {
  incidentListOf,
  landingPath,
  listReturnPath,
  PATHS,
  routeAccess,
  signInReturnPath,
} from './access';

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
    expect(landingPath('TECHNICIAN')).toBe(`${PATHS.workQueue}?status=ASSIGNED&status=IN_PROGRESS`);
    expect(landingPath('ADMINISTRATOR')).toBe(PATHS.users);
    expect(landingPath('ANALYST')).toBe(PATHS.reports);
    expect(landingPath('CITIZEN')).toBe(PATHS.useMobile);
  });
});

describe('listReturnPath', () => {
  it('goes back to the list as the user left it', () => {
    expect(listReturnPath({ listSearch: '?priority=HIGH&page=2' }, 'inbox')).toBe(
      `${PATHS.inbox}?priority=HIGH&page=2`,
    );
    expect(listReturnPath({ listSearch: '' }, 'inbox')).toBe(PATHS.inbox);
    expect(listReturnPath({ listSearch: '?page=1' }, 'workQueue')).toBe(
      `${PATHS.workQueue}?page=1`,
    );
  });

  it('goes to the default list after a direct link', () => {
    expect(listReturnPath(null, 'inbox')).toBe(`${PATHS.inbox}?status=REPORTED`);
    expect(listReturnPath({ listSearch: 42 }, 'inbox')).toBe(`${PATHS.inbox}?status=REPORTED`);
    expect(listReturnPath(null, 'workQueue')).toBe(
      `${PATHS.workQueue}?status=ASSIGNED&status=IN_PROGRESS`,
    );
  });
});

describe('incidentListOf', () => {
  it('leads the technician back to the work queue and the operators to the inbox', () => {
    expect(incidentListOf('TECHNICIAN')).toBe('workQueue');
    expect(incidentListOf('MUNICIPAL_OPERATOR')).toBe('inbox');
    expect(incidentListOf('ADMINISTRATOR')).toBe('inbox');
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
