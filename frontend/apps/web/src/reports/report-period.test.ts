import { describe, expect, it } from 'vitest';

import { csvFileName, defaultRequest, periodError, toReportQuery } from './report-period';

describe('report period', () => {
  it('opens on the last 30 days of Málaga, even when it is already tomorrow there', () => {
    // 23:30 UTC on 31 October is 00:30 on 1 November in Málaga (winter time).
    expect(defaultRequest(new Date('2026-10-31T23:30:00Z'))).toEqual({
      fromDay: '2026-10-03',
      toDay: '2026-11-01',
      groupBy: 'status',
    });
  });

  it('sends the start of the first day and the start of the day after the last one', () => {
    expect(
      toReportQuery({ fromDay: '2026-10-01', toDay: '2026-10-31', groupBy: 'district' }),
    ).toEqual({
      // Summer time in Málaga on 1 October, winter time on 1 November.
      from: '2026-09-30T22:00:00.000Z',
      to: '2026-10-31T23:00:00.000Z',
      groupBy: 'district',
    });
  });

  it('accepts one single day and a whole leap year', () => {
    expect(periodError({ fromDay: '2026-11-08', toDay: '2026-11-08', groupBy: 'status' })).toBe(
      undefined,
    );
    expect(periodError({ fromDay: '2028-01-01', toDay: '2028-12-31', groupBy: 'status' })).toBe(
      undefined,
    );
  });

  it('refuses a last day before the first one, as the API does', () => {
    expect(periodError({ fromDay: '2026-11-08', toDay: '2026-11-07', groupBy: 'status' })).toBe(
      'The last day must be on or after the first day.',
    );
  });

  it('refuses more than 366 days', () => {
    expect(periodError({ fromDay: '2026-01-01', toDay: '2027-01-02', groupBy: 'status' })).toBe(
      'The period may not be longer than 366 days.',
    );
  });

  // The user guide: the limit is counted in hours, as the API does, so 366 days that start in
  // summer time and end in winter time are one hour too long, and one day less is accepted.
  it('counts the limit in hours across a change to winter time', () => {
    // Summer time on 30 October 2027 (it ends on the 31st); winter time on 30 October 2028.
    expect(periodError({ fromDay: '2027-10-30', toDay: '2028-10-29', groupBy: 'status' })).toBe(
      'The period may not be longer than 366 days.',
    );
    expect(periodError({ fromDay: '2027-10-30', toDay: '2028-10-28', groupBy: 'status' })).toBe(
      undefined,
    );
  });

  it('asks for both days when one is missing or not a real day', () => {
    expect(periodError({ fromDay: '', toDay: '2026-11-08', groupBy: 'status' })).toBe(
      'Choose the first and the last day of the period.',
    );
    expect(periodError({ fromDay: '2026-02-30', toDay: '2026-03-08', groupBy: 'status' })).toBe(
      'Choose the first and the last day of the period.',
    );
  });

  it('names the file after the grouping and the days', () => {
    expect(csvFileName({ fromDay: '2026-10-01', toDay: '2026-10-31', groupBy: 'category' })).toBe(
      'incident-report-category-2026-10-01-to-2026-10-31.csv',
    );
  });
});
