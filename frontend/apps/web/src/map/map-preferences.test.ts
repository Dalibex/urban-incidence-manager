import { describe, expect, it } from 'vitest';

import {
  DEFAULT_PREFERENCES,
  isOpen,
  isShown,
  parsePreferences,
  withPanel,
  withShown,
} from './map-preferences';

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
      hidden: [],
    });
  });

  describe('hidden parts (#265)', () => {
    it('shows every part by default', () => {
      expect(isShown(DEFAULT_PREFERENCES, 'priority')).toBe(true);
      expect(isShown(DEFAULT_PREFERENCES, 'latest')).toBe(true);
      expect(DEFAULT_PREFERENCES.hidden).toEqual([]);
    });

    it('remembers a hidden part and shows it again', () => {
      const hidden = withShown(DEFAULT_PREFERENCES, 'department', false);
      expect(isShown(hidden, 'department')).toBe(false);
      expect(isShown(hidden, 'status')).toBe(true);
      expect(isShown(withShown(hidden, 'department', true), 'department')).toBe(true);
    });

    it('lists a part once however many times it is hidden', () => {
      const twice = withShown(withShown(DEFAULT_PREFERENCES, 'status', false), 'status', false);
      expect(twice.hidden).toEqual(['status']);
    });

    it('keeps a folded part folded when it is hidden and shown again', () => {
      const folded = withPanel(DEFAULT_PREFERENCES, 'category', false);
      const back = withShown(withShown(folded, 'category', false), 'category', true);
      expect(isOpen(back, 'category')).toBe(false);
    });

    it('reads back the hidden parts it stored', () => {
      const stored = withShown(withShown(DEFAULT_PREFERENCES, 'latest', false), 'status', false);
      expect(parsePreferences(JSON.stringify(stored))).toEqual(stored);
    });

    it('ignores what is not a part that can be hidden, the map key included', () => {
      expect(
        parsePreferences('{"folded":[],"opened":[],"hidden":["status","key","nonsense",3]}').hidden,
      ).toEqual(['status']);
    });

    it('hides nothing for a value stored before parts could be hidden (#177)', () => {
      expect(parsePreferences('{"folded":["category"],"opened":["key"]}')).toEqual({
        folded: ['category'],
        opened: ['key'],
        hidden: [],
      });
    });

    it('hides nothing when the stored hidden parts are not a list', () => {
      expect(parsePreferences('{"folded":[],"opened":[],"hidden":"status"}').hidden).toEqual([]);
    });
  });
});
