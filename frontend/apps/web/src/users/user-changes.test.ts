import { describe, expect, it } from 'vitest';

import { changesOf, STAFF_ROLES } from './user-changes';

const USER = {
  id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
  name: 'Pedro Gómez',
  email: 'pedro.gomez@example.com',
  role: 'TECHNICIAN',
  enabled: true,
} as const;

describe('changes of a user', () => {
  it('is null when nothing changed, so nothing is sent', () => {
    expect(changesOf(USER, 'Pedro Gómez', 'TECHNICIAN')).toBeNull();
  });

  it('is null when only spaces around the name changed', () => {
    expect(changesOf(USER, '  Pedro Gómez ', 'TECHNICIAN')).toBeNull();
  });

  it('holds only the name when only the name changed', () => {
    expect(changesOf(USER, 'Pedro G.', 'TECHNICIAN')).toEqual({ name: 'Pedro G.' });
  });

  it('holds only the role when only the role changed', () => {
    expect(changesOf(USER, 'Pedro Gómez', 'ANALYST')).toEqual({ role: 'ANALYST' });
  });

  it('holds both when both changed, and never the status', () => {
    expect(changesOf(USER, 'Pedro G.', 'ANALYST')).toEqual({ name: 'Pedro G.', role: 'ANALYST' });
  });

  it('offers no citizen role', () => {
    expect(STAFF_ROLES).not.toContain('CITIZEN');
  });
});
