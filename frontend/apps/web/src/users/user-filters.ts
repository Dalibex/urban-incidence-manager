import type { UserListQuery } from '@urbanpulse/api-client';
import { ROLES, type Role } from '@urbanpulse/domain';

/**
 * The filters of the users list (#191), as the screen keeps them in the address. The texts match a
 * part of the name or the email, ignoring case. Blank texts do not filter.
 */
export interface UserFilters {
  name?: string;
  email?: string;
  /** Any of these roles, in the order of `ROLES` and never empty. */
  role?: readonly Role[];
}

type UserFilterName = keyof UserFilters;

/** Every filter; also the names of the URL parameters. */
const FILTER_NAMES: readonly UserFilterName[] = ['name', 'email', 'role'];

function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

/** The roles to filter by, each once and in a fixed order, so the same choice gives one address. */
export function rolesFrom(roles: readonly Role[]): readonly Role[] | undefined {
  const chosen = ROLES.filter((role) => roles.includes(role));
  return chosen.length > 0 ? chosen : undefined;
}

/**
 * The filters of an address. A role the API would reject is dropped, never sent: the list then
 * shows more users, never an error.
 */
export function filtersFrom(params: URLSearchParams): UserFilters {
  const textOf = (name: string) =>
    params
      .getAll(name)
      .map((value) => value.trim())
      .find((value) => value !== '');
  return withoutEmpty({
    name: textOf('name'),
    email: textOf('email'),
    role: rolesFrom(params.getAll('role').filter(isRole)),
  });
}

/** The address of the filters: `params` with its filter parameters replaced by `filters`. */
export function withFilters(params: URLSearchParams, filters: UserFilters): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const name of FILTER_NAMES) {
    next.delete(name);
    const value = filters[name];
    const values = typeof value === 'string' ? [value] : (value ?? []);
    for (const each of values) {
      if (each) {
        next.append(name, each);
      }
    }
  }
  return next;
}

/** The filters as the query of `listUsers`. */
export function toListQuery(filters: UserFilters): UserListQuery {
  const { name, email, role } = filters;
  return {
    ...(name && { name }),
    ...(email && { email }),
    ...(role && { role: [...role] }),
  };
}

export function hasFilters(filters: UserFilters): boolean {
  return FILTER_NAMES.some((name) => filters[name] !== undefined);
}

/** The filters without the ones left empty, so `{ role: [] }` or `{ name: '' }` read as no filter. */
export function withoutEmpty(filters: UserFilters): UserFilters {
  return Object.fromEntries(
    Object.entries(filters).filter(
      ([, value]) =>
        value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0),
    ),
  ) as UserFilters;
}
