import { afterEach, describe, expect, it, vi } from 'vitest';

import { rememberedFilters, rememberFilters } from './remembered-filters';

const CATEGORY = '7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('remembered filters (#263)', () => {
  it('gives back the filters a user left on a list', () => {
    const filters = {
      status: ['ASSIGNED', 'IN_PROGRESS'] as const,
      categoryId: CATEGORY,
      priority: 'HIGH' as const,
      reportedFrom: '2026-11-01',
      reportedTo: '2026-11-05',
    };

    rememberFilters('inbox', 'user-1', filters);

    expect(rememberedFilters('inbox', 'user-1')).toEqual(filters);
  });

  it('tells nothing stored from no filters, which is what Clear filters leaves', () => {
    expect(rememberedFilters('inbox', 'user-1')).toBeUndefined();

    rememberFilters('inbox', 'user-1', {});

    expect(rememberedFilters('inbox', 'user-1')).toEqual({});
  });

  it('drops what the API would reject, never sends it', () => {
    window.localStorage.setItem(
      'urbanpulse.list-filters.inbox.user-1',
      `?status=NOPE&status=REPORTED&categoryId=deleted&priority=URGENT&reportedFrom=2026-02-30&departmentId=${CATEGORY}`,
    );

    expect(rememberedFilters('inbox', 'user-1')).toEqual({
      status: ['REPORTED'],
      departmentId: CATEGORY,
    });
  });

  it('reads garbage as no filters, not as an error', () => {
    window.localStorage.setItem('urbanpulse.list-filters.map.user-1', '{"not":"a query"}');

    expect(rememberedFilters('map', 'user-1')).toEqual({});
  });

  it('keeps each list and each user apart', () => {
    rememberFilters('inbox', 'user-1', { priority: 'HIGH' });
    rememberFilters('map', 'user-1', { priority: 'LOW' });

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

    expect(() => rememberFilters('workQueue', 'user-1', { priority: 'HIGH' })).not.toThrow();
    expect(rememberedFilters('workQueue', 'user-1')).toBeUndefined();
  });
});
