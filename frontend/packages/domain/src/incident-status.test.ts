import { describe, expect, it } from 'vitest';

import { INCIDENT_STATUSES, isIncidentStatus } from './incident-status';

describe('isIncidentStatus', () => {
  it('accepts every lifecycle state', () => {
    for (const status of INCIDENT_STATUSES) {
      expect(isIncidentStatus(status)).toBe(true);
    }
  });

  it('rejects unknown values, other spellings and non-strings', () => {
    expect(isIncidentStatus('IN PROGRESS')).toBe(false);
    expect(isIncidentStatus('reported')).toBe(false);
    expect(isIncidentStatus(undefined)).toBe(false);
    expect(isIncidentStatus(3)).toBe(false);
  });
});
