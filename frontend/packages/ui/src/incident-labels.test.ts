import { describe, expect, it } from 'vitest';

import { formatInstant, historyEventLabel } from './incident-labels';

describe('historyEventLabel', () => {
  it.each([
    [null, 'REPORTED', 'Reported'],
    ['REPORTED', 'REPORTED', 'Information requested'],
    ['REPORTED', 'VALIDATED', 'Validated'],
    ['VALIDATED', 'ASSIGNED', 'Assigned'],
    ['ASSIGNED', 'ASSIGNED', 'Reassigned'],
    ['ASSIGNED', 'IN_PROGRESS', 'Work started'],
    ['RESOLVED', 'REOPENED', 'Reopened'],
  ] as const)('names %s -> %s "%s"', (previous, next, label) => {
    expect(historyEventLabel(previous, next)).toBe(label);
  });
});

describe('formatInstant', () => {
  it('shows a UTC instant in the time of Málaga, whatever the zone of the browser', () => {
    expect(formatInstant('2026-11-02T09:58:31Z')).toBe('2 Nov 2026, 10:58');
  });
});
