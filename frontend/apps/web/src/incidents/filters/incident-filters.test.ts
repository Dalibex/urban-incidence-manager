import { describe, expect, it } from 'vitest';

import {
  filtersFrom,
  filtersSearch,
  hasFilters,
  idsFrom,
  searchFrom,
  searchTextProblem,
  toListQuery,
  withFilters,
  withSearch,
  type IncidentFilters,
} from './incident-filters';

const CATEGORY = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
const DEPARTMENT = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e';
// Sorts before CATEGORY and DEPARTMENT, to tell the order of the address from the order it came in.
const FIRST = '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d';

function from(query: string): IncidentFilters {
  return filtersFrom(new URLSearchParams(query));
}

describe('filtersFrom', () => {
  it('reads every filter of the address', () => {
    expect(
      from(
        `status=REPORTED&categoryId=${CATEGORY}&priority=HIGH&departmentId=${DEPARTMENT}` +
          '&reportedFrom=2026-11-01&reportedTo=2026-11-05',
      ),
    ).toEqual({
      status: ['REPORTED'],
      categoryId: [CATEGORY],
      priority: 'HIGH',
      departmentId: [DEPARTMENT],
      reportedFrom: '2026-11-01',
      reportedTo: '2026-11-05',
    });
  });

  it('has no filter for an address without them', () => {
    expect(from('page=3')).toEqual({});
  });

  it.each([
    ['an unknown status', 'status=FOO'],
    ['a status in lower case', 'status=reported'],
    ['an unknown priority', 'priority=URGENT'],
    ['an id that is not a UUID', 'categoryId=42'],
    ['an empty value', 'departmentId='],
    ['a day that does not exist', 'reportedFrom=2026-02-30'],
    ['a date-time instead of a day', 'reportedTo=2026-11-01T00:00:00Z'],
    ['a year before 1000', 'reportedFrom=0026-11-01'],
    ['the last day of year 9999', 'reportedTo=9999-12-31'],
  ])('drops %s', (_, query) => {
    expect(from(query)).toEqual({});
  });

  it('keeps the valid filters next to a dropped one', () => {
    expect(from('status=FOO&priority=LOW')).toEqual({ priority: 'LOW' });
  });

  // #265
  it('keeps every valid category and department, each once, lower case and sorted', () => {
    expect(
      from(
        `categoryId=${CATEGORY.toUpperCase()}&categoryId=42&categoryId=${FIRST}&categoryId=${CATEGORY}` +
          `&departmentId=${DEPARTMENT}&departmentId=${FIRST}`,
      ),
    ).toEqual({
      categoryId: [FIRST, CATEGORY],
      departmentId: [FIRST, DEPARTMENT],
    });
  });

  // #265: an address from before several values still opens.
  it('reads an address with a single category as a list of one', () => {
    expect(from(`categoryId=${CATEGORY}`)).toEqual({ categoryId: [CATEGORY] });
  });

  it('has no category filter when none of the repeated values is a UUID', () => {
    expect(from('categoryId=1&categoryId=two&departmentId=')).toEqual({});
  });

  it('keeps the first valid value of a repeated parameter', () => {
    expect(from('priority=URGENT&priority=LOW&priority=HIGH')).toEqual({ priority: 'LOW' });
  });

  // #217, T6
  it('keeps every valid status, each once and in the order of the lifecycle', () => {
    expect(from('status=CLOSED&status=FOO&status=ASSIGNED&status=CLOSED')).toEqual({
      status: ['ASSIGNED', 'CLOSED'],
    });
  });

  it('drops both days when the first comes after the last', () => {
    expect(from('reportedFrom=2026-11-05&reportedTo=2026-11-01')).toEqual({});
  });

  it('keeps a range of a single day', () => {
    expect(from('reportedFrom=2026-11-05&reportedTo=2026-11-05')).toEqual({
      reportedFrom: '2026-11-05',
      reportedTo: '2026-11-05',
    });
  });
});

describe('withFilters', () => {
  it('replaces the filters and keeps the other parameters', () => {
    const params = new URLSearchParams('status=REPORTED&page=4');

    const next = withFilters(params, { priority: 'HIGH' });

    expect(next.toString()).toBe('page=4&priority=HIGH');
  });

  it('round-trips through the address', () => {
    const filters: IncidentFilters = {
      status: ['ASSIGNED', 'IN_PROGRESS'],
      departmentId: [DEPARTMENT],
      reportedFrom: '2026-11-01',
    };

    expect(filtersFrom(withFilters(new URLSearchParams(), filters))).toEqual(filters);
  });

  // #265
  it('writes each category and department as a repeated parameter, in the order given', () => {
    const next = withFilters(new URLSearchParams(), {
      categoryId: [FIRST, CATEGORY],
      departmentId: [FIRST, DEPARTMENT],
    });

    expect(next.getAll('categoryId')).toEqual([FIRST, CATEGORY]);
    expect(next.getAll('departmentId')).toEqual([FIRST, DEPARTMENT]);
  });

  it('replaces the categories of the address instead of adding to them', () => {
    const params = new URLSearchParams(`categoryId=${CATEGORY}&categoryId=${FIRST}`);

    const next = withFilters(params, { categoryId: [DEPARTMENT] });

    expect(next.getAll('categoryId')).toEqual([DEPARTMENT]);
  });
});

// #265
describe('idsFrom', () => {
  it('gives each id once, in lower case and in string order, whatever order they came in', () => {
    expect(idsFrom([CATEGORY, FIRST.toUpperCase(), CATEGORY.toUpperCase()])).toEqual([
      FIRST,
      CATEGORY,
    ]);
  });

  it('is no filter when no id is given', () => {
    expect(idsFrom([])).toBeUndefined();
  });
});

describe('filtersSearch', () => {
  it('is the query of a link, or nothing without filters', () => {
    expect(filtersSearch({ status: ['REPORTED'] })).toBe('?status=REPORTED');
    expect(filtersSearch({ status: ['ASSIGNED', 'IN_PROGRESS'] })).toBe(
      '?status=ASSIGNED&status=IN_PROGRESS',
    );
    expect(filtersSearch({})).toBe('');
  });

  // #265
  it('repeats the parameter of a category chosen more than once', () => {
    expect(filtersSearch({ categoryId: [FIRST, CATEGORY] })).toBe(
      `?categoryId=${FIRST}&categoryId=${CATEGORY}`,
    );
  });
});

describe('toListQuery', () => {
  it('sends each filter as the contract expects', () => {
    expect(
      toListQuery({
        status: ['REPORTED', 'REOPENED'],
        categoryId: [CATEGORY],
        priority: 'HIGH',
        departmentId: [DEPARTMENT],
      }),
    ).toEqual({
      status: ['REPORTED', 'REOPENED'],
      categoryId: [CATEGORY],
      priority: ['HIGH'],
      departmentId: [DEPARTMENT],
    });
  });

  // #265
  it('sends every category and department chosen', () => {
    expect(
      toListQuery({ categoryId: [FIRST, CATEGORY], departmentId: [DEPARTMENT, CATEGORY] }),
    ).toEqual({ categoryId: [FIRST, CATEGORY], departmentId: [DEPARTMENT, CATEGORY] });
  });

  it('sends the days as the instants they start at in Málaga, the last one included', () => {
    expect(toListQuery({ reportedFrom: '2026-11-01', reportedTo: '2026-11-05' })).toEqual({
      reportedFrom: '2026-10-31T23:00:00.000Z',
      reportedTo: '2026-11-05T23:00:00.000Z',
    });
  });

  it('includes the last day of a month and of a year', () => {
    expect(toListQuery({ reportedTo: '2026-11-30' }).reportedTo).toBe('2026-11-30T23:00:00.000Z');
    expect(toListQuery({ reportedTo: '2026-12-31' }).reportedTo).toBe('2026-12-31T23:00:00.000Z');
  });

  it('sends nothing without filters', () => {
    expect(toListQuery({})).toEqual({});
  });
});

describe('hasFilters', () => {
  it('tells whether any filter is set', () => {
    expect(hasFilters({})).toBe(false);
    expect(hasFilters({ reportedTo: '2026-11-05' })).toBe(true);
  });
});

// #266: the text search of the map, `q` in the address and in the API.
describe('the text search', () => {
  it('reads it from the address without the spaces around it', () => {
    expect(searchFrom(new URLSearchParams('q=%20%20farola%20rota%20'))).toBe('farola rota');
  });

  it('drops a text the API would reject, so the map shows more, never an error', () => {
    expect(searchFrom(new URLSearchParams('q=ab'))).toBeUndefined();
    expect(searchFrom(new URLSearchParams('q=%20ab%20'))).toBeUndefined();
    expect(searchFrom(new URLSearchParams(`q=${'a'.repeat(101)}`))).toBeUndefined();
    expect(searchFrom(new URLSearchParams('q=ab%00c'))).toBeUndefined();
  });

  it('takes 3 and 100 characters, counting an emoji as one', () => {
    expect(searchFrom(new URLSearchParams('q=abc'))).toBe('abc');
    expect(searchFrom(new URLSearchParams(`q=${'a'.repeat(100)}`))).toBe('a'.repeat(100));
    // Counts where characters and UTF-16 units disagree: 100 emoji are 200 units, `a💡` is 3.
    expect(searchFrom(new URLSearchParams({ q: '💡'.repeat(100) }))).toBe('💡'.repeat(100));
    expect(searchFrom(new URLSearchParams({ q: 'a💡' }))).toBeUndefined();
    expect(searchTextProblem('a💡')).toBe('Type at least 3 characters.');
  });

  it('round-trips through the address and reaches the API', () => {
    const params = withSearch(new URLSearchParams('status=REPORTED&q=old'), '50% farola');
    expect(params.toString()).toBe('status=REPORTED&q=50%25+farola');
    expect(searchFrom(params)).toBe('50% farola');
    expect(withSearch(params, undefined).has('q')).toBe(false);
    expect(toListQuery({ q: '50% farola' })).toEqual({ q: '50% farola' });
    expect(hasFilters({ q: '50% farola' })).toBe(true);
  });

  // The lists have no box, and a list remembers the query of its filters (#263).
  it('is not a list filter: the lists ignore it and never keep it', () => {
    expect(filtersFrom(new URLSearchParams('status=REPORTED&q=farola'))).toEqual({
      status: ['REPORTED'],
    });
    expect(filtersSearch({ status: ['REPORTED'], q: 'farola' })).toBe('?status=REPORTED');
    expect(withFilters(new URLSearchParams('q=farola'), {}).get('q')).toBe('farola');
  });

  it('says why a text cannot be searched', () => {
    expect(searchTextProblem('farola')).toBeUndefined();
    expect(searchTextProblem('  ab ')).toBe('Type at least 3 characters.');
    expect(searchTextProblem('a'.repeat(101))).toBe('Type at most 100 characters.');
    expect(searchTextProblem('ab\u0000c')).toBe('Remove the null character.');
  });
});
