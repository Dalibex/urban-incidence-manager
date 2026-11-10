import { describe, expect, it } from 'vitest';

import { filtersFrom, hasFilters, toListQuery, withFilters } from './audit-filters';

const ACTOR = '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c';

describe('audit log filters', () => {
  it('reads every filter of the address', () => {
    const params = new URLSearchParams(
      `action=CHANGE_ROLE&action=CREATE_USER&actorId=${ACTOR}&from=2026-11-01&to=2026-11-05`,
    );

    expect(filtersFrom(params)).toEqual({
      action: ['CREATE_USER', 'CHANGE_ROLE'],
      actorId: ACTOR,
      from: '2026-11-01',
      to: '2026-11-05',
    });
  });

  it('drops the values the API would reject instead of sending them', () => {
    const params = new URLSearchParams('action=NOPE&actorId=not-a-uuid&from=2026-02-30');

    expect(filtersFrom(params)).toEqual({});
  });

  it('drops a range whose first day is after its last one', () => {
    const params = new URLSearchParams('from=2026-11-05&to=2026-11-01&action=CREATE_USER');

    expect(filtersFrom(params)).toEqual({ action: ['CREATE_USER'] });
  });

  it('writes the filters back to the address, keeping the other parameters', () => {
    const params = withFilters(new URLSearchParams('page=2&from=2026-01-01'), {
      action: ['CREATE_USER', 'CHANGE_ROLE'],
      actorId: ACTOR,
    });

    expect(params.get('page')).toBe('2');
    expect(params.has('from')).toBe(false);
    expect(params.getAll('action')).toEqual(['CREATE_USER', 'CHANGE_ROLE']);
    expect(params.get('actorId')).toBe(ACTOR);
  });

  // Málaga is UTC+1 in November; the API excludes `to`, so the last day ends at the next one.
  it('turns the days into the instants the API filters by', () => {
    expect(toListQuery({ from: '2026-11-01', to: '2026-11-05' })).toEqual({
      from: '2026-10-31T23:00:00.000Z',
      to: '2026-11-05T23:00:00.000Z',
    });
  });

  it('sends one day as a range the API accepts', () => {
    const { from, to } = toListQuery({ from: '2026-11-03', to: '2026-11-03' });

    expect(from! < to!).toBe(true);
  });

  it('tells whether any filter is used', () => {
    expect(hasFilters({})).toBe(false);
    expect(hasFilters({ actorId: ACTOR })).toBe(true);
  });
});
