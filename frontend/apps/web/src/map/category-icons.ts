import type { components } from '@urbanpulse/api-client';
import { colors } from '@urbanpulse/ui';
// Lucide, ISC licence (https://lucide.dev/license): each icon as plain drawing data, so the map
// (which needs an image) and the page (inline SVG) draw the same thing.
import {
  Armchair,
  Construction,
  Ellipsis,
  Lightbulb,
  Signpost,
  Trash2,
  Trees,
  type IconNode,
} from 'lucide';

type Category = components['schemas']['CategoryResponse'];
type CategoryIcon = components['schemas']['CategoryIcon'];

/** The icon of an incident without category, or of a category without an icon of its own. */
export const GENERIC_ICON = 'GENERIC';

export type IconKey = CategoryIcon | typeof GENERIC_ICON;

/** The drawing of each icon (#267). The API names the meaning; the drawing is this app's choice. */
const DRAWINGS: Record<IconKey, IconNode> = {
  STREET_LIGHTING: Lightbulb,
  PAVEMENT: Construction,
  WASTE: Trash2,
  URBAN_FURNITURE: Armchair,
  PARKS: Trees,
  SIGNAGE: Signpost,
  [GENERIC_ICON]: Ellipsis,
};

export const ICON_KEYS = Object.keys(DRAWINGS) as IconKey[];

/**
 * The icon of an incident's category. A category the list does not have (an inactive one, or the
 * list could not be loaded), or an icon this app cannot draw yet (a value added to the API after
 * this bundle was built), gets the generic icon, so a marker never goes without one.
 */
export function iconKeyOf(
  categoryId: string | null,
  categories: readonly Category[] | undefined,
): IconKey {
  const icon = categories?.find(({ id }) => id === categoryId)?.icon;
  return icon && Object.hasOwn(DRAWINGS, icon) ? icon : GENERIC_ICON;
}

/** The shapes of a drawing, in a 24 × 24 box, for an inline SVG. */
export function iconShapes(key: IconKey): IconNode {
  return DRAWINGS[key];
}

/** A little thicker than Lucide's 2, so the icon still reads at the size of a small marker. */
export const ICON_STROKE_WIDTH = 2.5;

/** The drawing as SVG markup, `size` pixels wide, in one colour: Lucide draws with strokes. */
export function iconSvg(key: IconKey, color: string, size: number): string {
  const shapes = DRAWINGS[key]
    .map(
      ([tag, attributes]) =>
        `<${tag} ${Object.entries(attributes)
          .map(([name, value]) => `${name}="${String(value)}"`)
          .join(' ')}/>`,
    )
    .join('');
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24"` +
    ` fill="none" stroke="${color}" stroke-width="${ICON_STROKE_WIDTH}" stroke-linecap="round"` +
    ` stroke-linejoin="round">${shapes}</svg>`
  );
}

/** The drawing as an image address, for the map and the charts, which take images, not markup. */
export function iconDataUrl(key: IconKey, color: string, size: number): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(iconSvg(key, color, size))}`;
}

/** The two colours an icon is drawn in on a fill: white and the dark text colour. */
export const GLYPH_COLORS = { light: '#ffffff', dark: colors.text } as const;

/**
 * White or the dark text colour, whichever contrasts more with `background` (WCAG relative
 * luminance), so an icon reads on the colour of any status.
 */
export function glyphColorOn(background: string): keyof typeof GLYPH_COLORS {
  const { light, dark } = GLYPH_COLORS;
  return contrast(background, light) >= contrast(background, dark) ? 'light' : 'dark';
}

function contrast(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (lighter + 0.05) / (darker + 0.05);
}

/** Relative luminance of a `#rrggbb` colour (WCAG 2.2). */
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((start) => {
    const channel = parseInt(hex.slice(start, start + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
