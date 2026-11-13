import { useCallback, useState } from 'react';

import { useSession } from '../session/SessionContext';

/** The parts of the map screen a user can fold or open (#177). */
export type MapPanel = 'priority' | 'status' | 'category' | 'department' | 'latest' | 'key';

/** How the map screen is laid out for a user, kept between visits. */
export interface MapPreferences {
  /** The panels the user folded; the map key is folded unless it is in `opened`. */
  folded: readonly MapPanel[];
  /** The panels folded by default that the user opened. */
  opened: readonly MapPanel[];
}

export const DEFAULT_PREFERENCES: MapPreferences = { folded: [], opened: [] };

/** The panels that start folded: the key, so it does not cover the map. */
const FOLDED_BY_DEFAULT: readonly MapPanel[] = ['key'];

const PANELS: readonly MapPanel[] = [
  'priority',
  'status',
  'category',
  'department',
  'latest',
  'key',
];

function isPanel(value: unknown): value is MapPanel {
  return typeof value === 'string' && (PANELS as readonly string[]).includes(value);
}

export function storageKey(userId: string): string {
  return `urbanpulse.map-preferences.${userId}`;
}

/**
 * The preferences stored for a user. Anything that is not what this version wrote (another
 * version, a hand edit, nothing at all) gives the defaults, never an error.
 */
export function parsePreferences(stored: string | null): MapPreferences {
  if (!stored) {
    return DEFAULT_PREFERENCES;
  }
  try {
    const value: unknown = JSON.parse(stored);
    if (typeof value !== 'object' || value === null) {
      return DEFAULT_PREFERENCES;
    }
    const { folded, opened } = value as Record<string, unknown>;
    return {
      folded: Array.isArray(folded) ? folded.filter(isPanel) : [],
      opened: Array.isArray(opened) ? opened.filter(isPanel) : [],
    };
  } catch {
    return DEFAULT_PREFERENCES;
  }
}

export function isOpen(preferences: MapPreferences, panel: MapPanel): boolean {
  return FOLDED_BY_DEFAULT.includes(panel)
    ? preferences.opened.includes(panel)
    : !preferences.folded.includes(panel);
}

/** The preferences with `panel` open or folded. */
export function withPanel(
  preferences: MapPreferences,
  panel: MapPanel,
  open: boolean,
): MapPreferences {
  const without = (panels: readonly MapPanel[]) => panels.filter((each) => each !== panel);
  if (FOLDED_BY_DEFAULT.includes(panel)) {
    return {
      ...preferences,
      opened: open ? [...without(preferences.opened), panel] : without(preferences.opened),
    };
  }
  return {
    ...preferences,
    folded: open ? without(preferences.folded) : [...without(preferences.folded), panel],
  };
}

// The browser may refuse its storage (a private window, blocked site data): the screen then works
// with the defaults and simply does not remember.
function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, preferences: MapPreferences): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(preferences));
  } catch {
    // Not remembered; the screen keeps working with what is on it.
  }
}

/**
 * Which panels of the map screen are open, remembered in this browser for the signed-in user. Each
 * user of a shared computer gets their own. They are a convenience, not data: nothing of them goes
 * to the API, and they hold nothing personal. Two tabs of the map each keep their own copy, and the
 * last one to change something is what the next visit finds.
 */
export function useMapPreferences() {
  const { user } = useSession();
  const key = user ? storageKey(user.id) : null;
  const [preferences, setPreferences] = useState<MapPreferences>(() =>
    key ? parsePreferences(read(key)) : DEFAULT_PREFERENCES,
  );

  const setPanel = useCallback(
    (panel: MapPanel, open: boolean) => {
      // The browser reports an open `<details>` as toggled when it is first drawn; that is not a
      // change, so nothing is written.
      if (isOpen(preferences, panel) === open) {
        return;
      }
      const next = withPanel(preferences, panel, open);
      setPreferences(next);
      if (key) {
        write(key, next);
      }
    },
    [key, preferences],
  );

  return { isOpen: (panel: MapPanel) => isOpen(preferences, panel), setPanel };
}
