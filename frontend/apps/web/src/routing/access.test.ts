import { ROLES } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { PATHS, landingPath, routeAccess } from './access';

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
  it('lands each role on its own screen', () => {
    expect(landingPath('MUNICIPAL_OPERATOR')).toBe(PATHS.inbox);
    expect(landingPath('TECHNICIAN')).toBe(PATHS.workQueue);
    expect(landingPath('ADMINISTRATOR')).toBe(PATHS.users);
    expect(landingPath('ANALYST')).toBe(PATHS.reports);
    expect(landingPath('CITIZEN')).toBe(PATHS.useMobile);
  });
});
