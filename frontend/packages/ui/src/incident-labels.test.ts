import { describe, expect, it } from 'vitest';

import { addressText, formatInstant, historyEventLabel, startOfDay } from './incident-labels';

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

describe('startOfDay', () => {
  it.each([
    ['in winter', '2026-11-02', '2026-11-01T23:00:00.000Z'],
    ['in summer', '2026-07-01', '2026-06-30T22:00:00.000Z'],
    ['on the day summer time starts', '2026-03-29', '2026-03-28T23:00:00.000Z'],
    ['on the day summer time ends', '2026-10-25', '2026-10-24T22:00:00.000Z'],
    ['on the first day of a year', '2027-01-01', '2026-12-31T23:00:00.000Z'],
  ])('is the local midnight of Málaga %s', (_, day, instant) => {
    expect(startOfDay(day)).toBe(instant);
  });
});

describe('addressText', () => {
  it('is the address while it is available', () => {
    expect(addressText('Calle Larios, 5, 29005 Málaga', 'AVAILABLE')).toEqual({
      text: 'Calle Larios, 5, 29005 Málaga',
      missing: false,
    });
  });

  it.each([
    ['PENDING', 'Looking up the address…'],
    ['OUTSIDE_AREA', 'No address: the position is outside Málaga'],
    ['UNAVAILABLE', 'Not available'],
  ] as const)('says why there is none when it is %s', (status, text) => {
    expect(addressText(null, status)).toEqual({ text, missing: true });
  });

  it('never shows a value left with another status as current', () => {
    expect(addressText('Calle Larios, 5', 'PENDING')).toEqual({
      text: 'Looking up the address…',
      missing: true,
    });
  });

  it('is not available when an available address comes without a value', () => {
    expect(addressText(null, 'AVAILABLE')).toEqual({ text: 'Not available', missing: true });
  });
});
