import { useEffect } from 'react';

import { useSession } from '../../session/SessionContext';
import { filtersFrom, filtersSearch, type IncidentFilters } from './incident-filters';

/** The screens that remember the filters their user last used on them (#263). */
export type FilterList = 'inbox' | 'workQueue' | 'map';

function storageKey(list: FilterList, userId: string): string {
  return `urbanpulse.list-filters.${list}.${userId}`;
}

// The browser may refuse its storage (a private window, blocked site data): the menu then opens
// the defaults and the screens simply do not remember.
function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, search: string): void {
  try {
    window.localStorage.setItem(key, search);
  } catch {
    // Not remembered; the screen keeps working with what is on it.
  }
}

const listeners = new Set<() => void>();
let version = 0;

/** For `useSyncExternalStore`: the menu reads again when a screen has just remembered something. */
export function subscribeToRemembered(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function rememberedVersion(): number {
  return version;
}

/**
 * The filters a user last used on a list, or `undefined` when nothing is stored, which is not the
 * same as `{}`: a user who cleared the filters wants none. Stored values are read like an address,
 * so anything the API would reject is dropped.
 */
export function rememberedFilters(list: FilterList, userId: string): IncidentFilters | undefined {
  const stored = read(storageKey(list, userId));
  return stored === null ? undefined : filtersFrom(new URLSearchParams(stored));
}

/**
 * Where a list opens for a user: with the filters they last used on it, or at `firstVisit` when
 * nothing is stored. Nothing stored is not the same as no filters: after *Clear filters* the list
 * opens as is.
 */
export function rememberedPath(
  path: string,
  list: FilterList,
  userId: string,
  firstVisit: string,
): string {
  const filters = rememberedFilters(list, userId);
  return filters ? path + filtersSearch(filters) : firstVisit;
}

export function rememberFilters(list: FilterList, userId: string, search: string): void {
  write(storageKey(list, userId), search);
  version += 1;
  listeners.forEach((listener) => listener());
}

/**
 * Remembers the filters on screen for the signed-in user, in this browser. Each user of a shared
 * computer gets their own key, so nothing needs clearing on sign-out. They are a convenience, not
 * data: nothing of them goes to the API, and they hold nothing personal.
 */
export function useRememberFilters(list: FilterList, filters: IncidentFilters): void {
  const { user } = useSession();
  const userId = user?.id;
  const search = filtersSearch(filters);
  useEffect(() => {
    if (userId) {
      rememberFilters(list, userId, search);
    }
  }, [list, userId, search]);
}
