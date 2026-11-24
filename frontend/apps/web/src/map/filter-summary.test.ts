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
          categoryId: [CATEGORY.id],
          priority: 'HIGH',
          departmentId: [DEPARTMENT.id],
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
    expect(filterSummary({ categoryId: [CATEGORY.id], departmentId: [DEPARTMENT.id] })).toEqual([
      'Category: selected',
      'Department: selected',
    ]);
  });

  // #265
  it('lists every category and department chosen, in the order of the options', () => {
    const lighting = { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' };
    const parks = { id: 'c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f', name: 'Parks' };
    const water = { id: 'd4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f7a', name: 'Water' };

    expect(
      filterSummary(
        { categoryId: [lighting.id, parks.id], departmentId: [water.id, DEPARTMENT.id] },
        [parks, lighting],
        [DEPARTMENT, water],
      ),
    ).toEqual(['Category: Parks, Street lighting', 'Department: Public works, Water']);
  });

  it('puts an id that is not among the loaded options last, named as unknown like the bar', () => {
    const unknown = 'e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b';

    expect(
      filterSummary(
        { categoryId: [unknown, CATEGORY.id], departmentId: [unknown] },
        [CATEGORY],
        [DEPARTMENT],
      ),
    ).toEqual(['Category: Pavement, Unknown category', 'Department: Unknown department']);
  });

  it('numbers several unknown ids, as the bar does', () => {
    const ids = ['e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b', 'f0e1d2c3-b4a5-4968-8776-655443322110'];

    expect(filterSummary({ categoryId: [CATEGORY.id, ...ids] }, [CATEGORY], [])).toEqual([
      'Category: Pavement, Unknown category 1, Unknown category 2',
    ]);
  });

  // `undefined` is "not loaded yet" (what the query gives); an empty list is "loaded, none".
  it('counts several ids while the options are not loaded, instead of repeating selected', () => {
    const ids = ['a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', CATEGORY.id, DEPARTMENT.id];

    expect(
      filterSummary({ categoryId: ids, departmentId: ids.slice(0, 2) }, undefined, undefined),
    ).toEqual(['Category: 3 categories', 'Department: 2 departments']);
  });

  it('calls an id unknown when the options loaded empty', () => {
    expect(filterSummary({ categoryId: [CATEGORY.id] }, [], [])).toEqual([
      'Category: Unknown category',
    ]);
  });

  it('says an open range of days and a single day as people read them', () => {
    expect(filterSummary({ reportedFrom: '2026-11-01' })).toEqual(['Reported from 2026-11-01']);
    expect(filterSummary({ reportedTo: '2026-11-05' })).toEqual(['Reported until 2026-11-05']);
    expect(filterSummary({ reportedFrom: '2026-11-03', reportedTo: '2026-11-03' })).toEqual([
      'Reported on 2026-11-03',
    ]);
  });

  it('names the text search, so a folded bar does not hide it (#266)', () => {
    expect(filterSummary({ status: ['REPORTED'], q: 'farola' })).toEqual([
      'Search: “farola”',
      'Status: Reported',
    ]);
  });
});
