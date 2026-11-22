import { INCIDENT_STATUSES, type IncidentStatus } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { statusColor } from './status-color';
import { colors } from './tokens';

/** WCAG AA for normal text (WCAG 2.1, 1.4.3), the rule T2 of #303 asks for. */
const AA_TEXT = 4.5;

/** The page colors a status is drawn on: the white page and the `surface` of panels and lists. */
const PAGE_COLORS = { background: colors.background, surface: colors.surface } as const;
type Page = keyof typeof PAGE_COLORS;

/** Relative luminance of a `#RRGGBB` color, as WCAG 2.1 defines it. */
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((start) => {
    const c = parseInt(hex.slice(start, start + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

/** WCAG contrast ratio between two colors, from 1 to 21. */
function contrast(first: string, second: string): number {
  const [light, dark] = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (light! + 0.05) / (dark! + 0.05);
}

const STATUS_ON_PAGE: [IncidentStatus, Page][] = INCIDENT_STATUSES.flatMap((status) =>
  (Object.keys(PAGE_COLORS) as Page[]).map((page): [IncidentStatus, Page] => [status, page]),
);

describe('statusColor', () => {
  it('gives every status a color of its own', () => {
    const used = INCIDENT_STATUSES.map((status) => statusColor(status).toUpperCase());

    expect(new Set(used).size).toBe(INCIDENT_STATUSES.length);
  });
});

// T2 of #303. The label of a badge is `colors.text` on the page, with the status color as a dot
// beside it (web `.status::before`, mobile `StatusBadge`), so both have to stand out from the page.
describe('contrast of the status badges (WCAG AA, light mode)', () => {
  it('computes the contrast ratio as WCAG does', () => {
    expect(contrast('#000000', '#FFFFFF')).toBeCloseTo(21, 5);
    expect(contrast('#FFFFFF', '#FFFFFF')).toBeCloseTo(1, 5);
    // The lightest grey that still meets AA on white, a known reference value.
    expect(contrast('#767676', '#FFFFFF')).toBeCloseTo(4.54, 2);
  });

  it.each(STATUS_ON_PAGE)('draws %s with at least 4.5:1 on %s', (status, page) => {
    const color = statusColor(status);

    expect(
      contrast(color, PAGE_COLORS[page]),
      `${status} ${color} on ${page}`,
    ).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it.each(Object.keys(PAGE_COLORS) as Page[])(
    'writes the label with at least 4.5:1 on %s',
    (page) => {
      expect(contrast(colors.text, PAGE_COLORS[page])).toBeGreaterThanOrEqual(AA_TEXT);
    },
  );
});
