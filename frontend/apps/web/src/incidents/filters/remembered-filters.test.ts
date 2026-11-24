import { afterEach, describe, expect, it, vi } from 'vitest';

import { filtersSearch } from './incident-filters';
import { rememberedFilters, rememberFilters } from './remembered-filters';

const CATEGORY = '7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('remembered filters (#263)', () => {
  it('gives back the filters a user left on a list', () => {
    const filters = {
      status: ['ASSIGNED', 'IN_PROGRESS'] as const,
      categoryId: [CATEGORY],
      priority: 'HIGH' as const,
      reportedFrom: '2026-11-01',
      reportedTo: '2026-11-05',
    };

    rememberFilters('inbox', 'user-1', filtersSearch(filters));

    expect(rememberedFilters('inbox', 'user-1')).toEqual(filters);
  });

  // #265: what a list stored before several categories held one value.
  it('reads a stored single category as a filter of one', () => {
    window.localStorage.setItem(
      'urbanpulse.list-filters.inbox.user-1',
      `?status=REPORTED&categoryId=${CATEGORY}`,
    );

    expect(rememberedFilters('inbox', 'user-1')).toEqual({
      status: ['REPORTED'],
      categoryId: [CATEGORY],
    });
  });

  // #265, test sub-issue T4: several categories and departments come back as they were left.
  it('gives back several categories and departments, each once and in order', () => {
    const OTHER_CATEGORY = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
    const DEPARTMENTS = [
      '0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d',
      'd4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f7a',
    ];
    const filters = { categoryId: [OTHER_CATEGORY, CATEGORY], departmentId: DEPARTMENTS };

    rememberFilters('workQueue', 'user-1', filtersSearch(filters));

    expect(rememberedFilters('workQueue', 'user-1')).toEqual(filters);
  });

  it('tells nothing stored from no filters, which is what Clear filters leaves', () => {
    expect(rememberedFilters('inbox', 'user-1')).toBeUndefined();

    rememberFilters('inbox', 'user-1', filtersSearch({}));

    expect(rememberedFilters('inbox', 'user-1')).toEqual({});
  });

  it('drops what the API would reject, such as a malformed id, never sends it', () => {
    window.localStorage.setItem(
      'urbanpulse.list-filters.inbox.user-1',
      `?status=NOPE&status=REPORTED&categoryId=not-an-id&priority=URGENT&reportedFrom=2026-02-30&departmentId=${CATEGORY}`,
    );

    expect(rememberedFilters('inbox', 'user-1')).toEqual({
      status: ['REPORTED'],
      departmentId: [CATEGORY],
    });
  });

  it('reads garbage as no filters, not as an error', () => {
    window.localStorage.setItem('urbanpulse.list-filters.map.user-1', '{"not":"a query"}');

    expect(rememberedFilters('map', 'user-1')).toEqual({});
  });

  it('keeps each list and each user apart', () => {
    rememberFilters('inbox', 'user-1', filtersSearch({ priority: 'HIGH' }));
    rememberFilters('map', 'user-1', filtersSearch({ priority: 'LOW' }));

    expect(rememberedFilters('inbox', 'user-1')).toEqual({ priority: 'HIGH' });
    expect(rememberedFilters('map', 'user-1')).toEqual({ priority: 'LOW' });
    expect(rememberedFilters('inbox', 'user-2')).toBeUndefined();
    expect(Object.keys(window.localStorage).sort()).toEqual([
      'urbanpulse.list-filters.inbox.user-1',
      'urbanpulse.list-filters.map.user-1',
    ]);
  });

  it('works without remembering when the browser refuses its storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });

    expect(() =>
      rememberFilters('workQueue', 'user-1', filtersSearch({ priority: 'HIGH' })),
    ).not.toThrow();
    expect(rememberedFilters('workQueue', 'user-1')).toBeUndefined();
  });
});
