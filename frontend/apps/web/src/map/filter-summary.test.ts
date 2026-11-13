import { describe, expect, it } from 'vitest';

import { filterSummary } from './filter-summary';

const CATEGORY = { id: '7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f', name: 'Pavement' };
const DEPARTMENT = { id: '5b2e9c1a-3d4f-4e6a-8b7c-9d0e1f2a3b4c', name: 'Public works' };

describe('filterSummary', () => {
  it('says nothing without filters', () => {
    expect(filterSummary({})).toEqual([]);
  });

  it('names every active filter, one entry each, with the names the screen uses', () => {
    expect(
      filterSummary(
        {
          status: ['REPORTED', 'IN_PROGRESS'],
          categoryId: CATEGORY.id,
          priority: 'HIGH',
          departmentId: DEPARTMENT.id,
          reportedFrom: '2026-11-01',
          reportedTo: '2026-11-05',
        },
        [CATEGORY],
        [DEPARTMENT],
      ),
    ).toEqual([
      'Status: Reported, In progress',
      'Category: Pavement',
      'Priority: High',
      'Department: Public works',
      'Reported from 2026-11-01 to 2026-11-05',
    ]);
  });

  it('still counts a category or department whose name has not loaded', () => {
    expect(filterSummary({ categoryId: CATEGORY.id, departmentId: DEPARTMENT.id })).toEqual([
      'Category: selected',
      'Department: selected',
    ]);
  });

  it('says an open range of days and a single day as people read them', () => {
    expect(filterSummary({ reportedFrom: '2026-11-01' })).toEqual(['Reported from 2026-11-01']);
    expect(filterSummary({ reportedTo: '2026-11-05' })).toEqual(['Reported until 2026-11-05']);
    expect(filterSummary({ reportedFrom: '2026-11-03', reportedTo: '2026-11-03' })).toEqual([
      'Reported on 2026-11-03',
    ]);
  });
});
