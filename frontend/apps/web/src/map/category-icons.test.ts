import { colors } from '@urbanpulse/ui';
import { describe, expect, it } from 'vitest';

import { GENERIC_ICON, GLYPH_COLORS, glyphColorOn, iconKeyOf, iconSvg } from './category-icons';

const LIGHTING = 'fd4a9835-80be-4b73-bc9f-a042bed62b61';
const OTHER = '474f62e6-521a-4958-ba99-e0d46b420233';
const CATEGORIES = [
  { id: LIGHTING, name: 'Street lighting', description: null, icon: 'STREET_LIGHTING' as const },
  { id: OTHER, name: 'Other', description: null, icon: null },
];

describe('iconKeyOf', () => {
  it('is the icon of the category of the incident', () => {
    expect(iconKeyOf(LIGHTING, CATEGORIES)).toBe('STREET_LIGHTING');
  });

  it('is the generic icon without category or when the category has no icon', () => {
    expect(iconKeyOf(null, CATEGORIES)).toBe(GENERIC_ICON);
    expect(iconKeyOf(OTHER, CATEGORIES)).toBe(GENERIC_ICON);
  });

  // An inactive category is not in the list, and the list may have failed to load: the marker
  // still draws, with the generic icon.
  it('is the generic icon for a category the list does not have, or with no list', () => {
    expect(iconKeyOf('00000000-0000-4000-8000-000000000000', CATEGORIES)).toBe(GENERIC_ICON);
    expect(iconKeyOf(LIGHTING, undefined)).toBe(GENERIC_ICON);
  });

  // A value added to the API after this bundle was built: an open tab still draws the marker.
  it('is the generic icon for an icon this app has no drawing for', () => {
    const newer = [{ ...CATEGORIES[0]!, icon: 'GRAFFITI' as unknown as 'WASTE' }];
    expect(iconKeyOf(LIGHTING, newer)).toBe(GENERIC_ICON);
  });
});

describe('glyphColorOn', () => {
  it('draws white on a dark fill and the dark text colour on a light one', () => {
    expect(glyphColorOn('#1d4ed8')).toBe('light');
    expect(glyphColorOn('#000000')).toBe('light');
    expect(glyphColorOn('#facc15')).toBe('dark');
    expect(glyphColorOn('#ffffff')).toBe('dark');
    expect(GLYPH_COLORS).toEqual({ light: '#ffffff', dark: colors.text });
  });
});

describe('iconSvg', () => {
  it('is an SVG of the drawing in the given colour, which an image can load', () => {
    const svg = iconSvg('WASTE', '#ffffff', 48);

    expect(svg).toMatch(/^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg"/);
    expect(svg).toContain('width="48"');
    expect(svg).toContain('stroke="#ffffff"');
    expect(svg).toContain('<path');
  });

  it('has a different drawing for each icon', () => {
    const drawings = new Set(
      (
        [
          'STREET_LIGHTING',
          'PAVEMENT',
          'WASTE',
          'URBAN_FURNITURE',
          'PARKS',
          'SIGNAGE',
          GENERIC_ICON,
        ] as const
      ).map((key) => iconSvg(key, '#000000', 24)),
    );
    expect(drawings.size).toBe(7);
  });
});
