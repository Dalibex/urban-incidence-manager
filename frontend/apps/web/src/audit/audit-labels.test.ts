import { describe, expect, it } from 'vitest';

import { ACTION_LABELS, AUDIT_ACTIONS, fieldChanges, formatAuditValue } from './audit-labels';

describe('audit details', () => {
  it('pairs the value each field had with the one it got', () => {
    expect(fieldChanges({ before: { role: 'TECHNICIAN' }, after: { role: 'ANALYST' } })).toEqual([
      { field: 'role', before: 'TECHNICIAN', after: 'ANALYST' },
    ]);
  });

  it('has no before for a field the change created', () => {
    expect(
      fieldChanges({ before: {}, after: { role: 'ADMINISTRATOR', origin: 'BOOTSTRAP' } }),
    ).toEqual([
      { field: 'role', after: 'ADMINISTRATOR' },
      { field: 'origin', after: 'BOOTSTRAP' },
    ]);
  });

  // A field without value is recorded as null: it is a value, not a missing side.
  it('keeps a null value apart from a missing one', () => {
    expect(fieldChanges({ before: { priority: null }, after: { priority: 'HIGH' } })).toEqual([
      { field: 'priority', before: null, after: 'HIGH' },
    ]);
    expect(formatAuditValue(null)).toBe('none');
  });

  it('writes booleans and numbers as text', () => {
    expect(formatAuditValue(true)).toBe('yes');
    expect(formatAuditValue(false)).toBe('no');
    expect(formatAuditValue(2)).toBe('2');
  });
});

describe('action labels', () => {
  it('names the second-factor actions and offers them in the filter', () => {
    expect(ACTION_LABELS.ENROLL_MFA).toBe('Second factor set up');
    expect(ACTION_LABELS.RESET_MFA).toBe('Second factor reset');
    expect(AUDIT_ACTIONS).toEqual(expect.arrayContaining(['ENROLL_MFA', 'RESET_MFA']));
  });
});
