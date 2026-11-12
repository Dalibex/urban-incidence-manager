import { describe, expect, it } from 'vitest';

import { filtersFrom, hasFilters, toListQuery, withFilters } from './user-filters';

describe('users list filters', () => {
  it('reads every filter of the address, with the roles in a fixed order', () => {
    const params = new URLSearchParams('name=ped&email=example&role=ANALYST&role=TECHNICIAN');

    expect(filtersFrom(params)).toEqual({
      name: 'ped',
      email: 'example',
      role: ['TECHNICIAN', 'ANALYST'],
    });
  });

  it('drops a role the API would reject and the texts that are blank', () => {
    const params = new URLSearchParams('role=NOPE&name=%20%20&email=');

    expect(filtersFrom(params)).toEqual({});
    expect(hasFilters(filtersFrom(params))).toBe(false);
  });

  it('trims the texts', () => {
    expect(filtersFrom(new URLSearchParams('name=%20ped%20'))).toEqual({ name: 'ped' });
  });

  it('writes the filters back to the address, keeping the other parameters', () => {
    const params = withFilters(new URLSearchParams('page=2&name=old'), {
      email: 'example',
      role: ['TECHNICIAN', 'ANALYST'],
    });

    expect(params.get('page')).toBe('2');
    expect(params.has('name')).toBe(false);
    expect(params.get('email')).toBe('example');
    expect(params.getAll('role')).toEqual(['TECHNICIAN', 'ANALYST']);
  });

  it('turns the filters into the query of the API, repeating the role', () => {
    expect(toListQuery({ name: 'ped', role: ['TECHNICIAN', 'ANALYST'] })).toEqual({
      name: 'ped',
      role: ['TECHNICIAN', 'ANALYST'],
    });
    expect(toListQuery({})).toEqual({});
  });
});
