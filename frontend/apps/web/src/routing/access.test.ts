import { ROLES } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import {
  detailBack,
  incidentListOf,
  landingPath,
  mapDefaultPath,
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

describe('mapDefaultPath', () => {
  it('opens the map of a technician on the same pending work as their queue', () => {
    expect(mapDefaultPath('TECHNICIAN')).toBe(`${PATHS.map}?status=ASSIGNED&status=IN_PROGRESS`);
  });

  it('opens the map of an operator or an administrator on every status that can still change', () => {
    const live =
      'status=REPORTED&status=VALIDATED&status=ASSIGNED&status=IN_PROGRESS&status=RESOLVED&status=REOPENED';
    expect(mapDefaultPath('MUNICIPAL_OPERATOR')).toBe(`${PATHS.map}?${live}`);
    expect(mapDefaultPath('ADMINISTRATOR')).toBe(`${PATHS.map}?${live}`);
  });
});

describe('detailBack', () => {
  it('goes back to the list as the user left it', () => {
    expect(
      detailBack({ from: 'inbox', search: '?priority=HIGH&page=2' }, 'MUNICIPAL_OPERATOR'),
    ).toEqual({
      to: 'inbox',
      path: `${PATHS.inbox}?priority=HIGH&page=2`,
    });
    expect(detailBack({ from: 'inbox', search: '' }, 'ADMINISTRATOR')).toEqual({
      to: 'inbox',
      path: PATHS.inbox,
    });
    expect(detailBack({ from: 'workQueue', search: '?page=1' }, 'TECHNICIAN')).toEqual({
      to: 'workQueue',
      path: `${PATHS.workQueue}?page=1`,
    });
  });

  it('goes to the default list of the role after a direct link', () => {
    const inbox = { to: 'inbox', path: `${PATHS.inbox}?status=REPORTED` };
    const queue = {
      to: 'workQueue',
      path: `${PATHS.workQueue}?status=ASSIGNED&status=IN_PROGRESS`,
    };
    expect(detailBack(null, 'MUNICIPAL_OPERATOR')).toEqual(inbox);
    expect(detailBack(undefined, 'ADMINISTRATOR')).toEqual(inbox);
    expect(detailBack(null, 'TECHNICIAN')).toEqual(queue);
  });

  it('goes to the default list of the role when the origin is not a staff screen', () => {
    const inbox = { to: 'inbox', path: `${PATHS.inbox}?status=REPORTED` };
    expect(detailBack({ from: 'inbox', search: 42 }, 'MUNICIPAL_OPERATOR')).toEqual(inbox);
    expect(detailBack({ from: 'users', search: '' }, 'MUNICIPAL_OPERATOR')).toEqual(inbox);
    expect(detailBack({ from: 'toString', search: '' }, 'MUNICIPAL_OPERATOR')).toEqual(inbox);
    expect(detailBack({ from: 'inbox', search: '/users' }, 'MUNICIPAL_OPERATOR')).toEqual(inbox);
    expect(detailBack({ listSearch: '?page=2' }, 'MUNICIPAL_OPERATOR')).toEqual(inbox);
  });

  it('goes to the default list of the role when the role cannot open the origin', () => {
    expect(detailBack({ from: 'inbox', search: '?page=2' }, 'TECHNICIAN')).toEqual({
      to: 'workQueue',
      path: `${PATHS.workQueue}?status=ASSIGNED&status=IN_PROGRESS`,
    });
    expect(detailBack({ from: 'workQueue', search: '' }, 'MUNICIPAL_OPERATOR')).toEqual({
      to: 'inbox',
      path: `${PATHS.inbox}?status=REPORTED`,
    });
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
    expect(signInReturnPath({ returnTo: `${PATHS.inbox}?priority=HIGH` }, 'user-1')).toBe(
      `${PATHS.inbox}?priority=HIGH`,
    );
  });

  it('goes back only for the user who left, another one goes to their own screen (#263)', () => {
    const state = { returnTo: `${PATHS.inbox}?priority=HIGH`, userId: 'user-1' };
    expect(signInReturnPath(state, 'user-1')).toBe(state.returnTo);
    expect(signInReturnPath(state, 'user-2')).toBe(PATHS.home);
  });

  it("goes to the user's own screen when the sign-in was opened directly", () => {
    expect(signInReturnPath(null, 'user-1')).toBe(PATHS.home);
    expect(signInReturnPath({ returnTo: 42 }, 'user-1')).toBe(PATHS.home);
  });
});
