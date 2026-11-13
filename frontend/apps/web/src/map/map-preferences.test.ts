import { describe, expect, it } from 'vitest';

import { DEFAULT_PREFERENCES, isOpen, parsePreferences, withPanel } from './map-preferences';

describe('map preferences', () => {
  it('opens every part of the summary and folds the key of the map by default', () => {
    expect(isOpen(DEFAULT_PREFERENCES, 'status')).toBe(true);
    expect(isOpen(DEFAULT_PREFERENCES, 'latest')).toBe(true);
    expect(isOpen(DEFAULT_PREFERENCES, 'key')).toBe(false);
  });

  it('remembers a folded part and an opened key, and undoes each', () => {
    const folded = withPanel(DEFAULT_PREFERENCES, 'category', false);
    expect(isOpen(folded, 'category')).toBe(false);
    expect(isOpen(withPanel(folded, 'category', true), 'category')).toBe(true);

    const keyOpen = withPanel(DEFAULT_PREFERENCES, 'key', true);
    expect(isOpen(keyOpen, 'key')).toBe(true);
    expect(isOpen(withPanel(keyOpen, 'key', false), 'key')).toBe(false);
  });

  it('reads back what it stored', () => {
    const stored = withPanel(withPanel(DEFAULT_PREFERENCES, 'status', false), 'key', true);
    expect(parsePreferences(JSON.stringify(stored))).toEqual(stored);
  });

  it('gives the defaults for anything it did not write, never an error', () => {
    expect(parsePreferences(null)).toEqual(DEFAULT_PREFERENCES);
    expect(parsePreferences('not json')).toEqual(DEFAULT_PREFERENCES);
    expect(parsePreferences('42')).toEqual(DEFAULT_PREFERENCES);
    expect(parsePreferences('{"folded":["status","nonsense",3],"opened":"key"}')).toEqual({
      folded: ['status'],
      opened: [],
    });
  });
});
