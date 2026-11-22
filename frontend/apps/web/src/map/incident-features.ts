import type { components } from '@urbanpulse/api-client';
import type { IncidentStatus } from '@urbanpulse/domain';
import { colors, statusColor } from '@urbanpulse/ui';

import { PRIORITY_LABELS } from '../incidents/labels';
import { GLYPH_COLORS, glyphColorOn, iconKeyOf, type IconKey } from './category-icons';

type IncidentPosition = components['schemas']['IncidentPosition'];
type Category = components['schemas']['CategoryResponse'];
type Priority = components['schemas']['Priority'];

/**
 * The priority as a number, so a cluster can keep the highest of its incidents with `max`: an
 * incident without priority is 0, below `LOW`.
 */
export const PRIORITY_RANK: Record<Priority, number> = { LOW: 1, MEDIUM: 2, HIGH: 3 };

export function priorityRank(priority: Priority | null): number {
  return priority ? PRIORITY_RANK[priority] : 0;
}

/** What each point of the map carries, read by the layers and by a click. */
export interface IncidentFeatureProperties {
  id: string;
  status: IncidentStatus;
  priorityRank: number;
  /** The shared color of the status, so the markers match the badges of the lists. */
  color: string;
  /** The image of the icon of its category ({@link markerIconId}). */
  icon: string;
}

/**
 * The name of the map image of an icon on a fill colour: its drawing, in white or dark, whichever
 * reads on that fill. The map adds one image per icon and glyph colour.
 */
export function markerIconId(key: IconKey, fill: string): string {
  return iconImageId(key, glyphColorOn(fill));
}

export function iconImageId(key: IconKey, glyph: keyof typeof GLYPH_COLORS): string {
  return `category-${key}-${glyph}`;
}

/** A GeoJSON collection of points (RFC 7946), the data of the map's clustered source. */
export interface IncidentFeatureCollection {
  type: 'FeatureCollection';
  features: {
    type: 'Feature';
    /** `[longitude, latitude]`, WGS 84. */
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: IncidentFeatureProperties;
  }[];
}

/**
 * The positions as GeoJSON points for a clustered source. GeoJSON orders a position as
 * [longitude, latitude] (RFC 7946), the opposite of how the API names them, so the swap happens
 * here and only here.
 */
export function toFeatureCollection(
  positions: readonly IncidentPosition[],
  categories?: readonly Category[],
): IncidentFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: positions.map((position) => {
      const color = statusColor(position.status);
      return {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [position.longitude, position.latitude] },
        properties: {
          id: position.id,
          status: position.status,
          priorityRank: priorityRank(position.priority),
          color,
          icon: markerIconId(iconKeyOf(position.categoryId, categories), color),
        },
      };
    }),
  };
}

/** How the map draws one level of priority, never with a color: the color is the status. */
export interface PriorityMark {
  rank: number;
  /** The level in words, as the lists say it; the label under a cluster with this highest level. */
  label: string;
  /**
   * Radius of an incident marker, in pixels: a higher priority draws a bigger marker. The smallest
   * still holds a readable category icon (#267).
   */
  radius: number;
}

/**
 * The marks of each priority, from none to `HIGH`. The layers and the legend of the map read this
 * one table, so they never disagree. A `HIGH` marker also has a dark border ({@link HIGH_BORDER}).
 */
export const PRIORITY_MARKS: readonly PriorityMark[] = [
  { rank: 0, label: 'No priority', radius: 8 },
  { rank: PRIORITY_RANK.LOW, label: PRIORITY_LABELS.LOW, radius: 10 },
  { rank: PRIORITY_RANK.MEDIUM, label: PRIORITY_LABELS.MEDIUM, radius: 12 },
  { rank: PRIORITY_RANK.HIGH, label: PRIORITY_LABELS.HIGH, radius: 14 },
];

/** The border that sets a `HIGH` incident apart, besides its size. */
export const HIGH_BORDER = { color: colors.text, width: 3 } as const;

/** The neutral fill of a cluster: it holds incidents of several statuses, so it has no status color. */
export const CLUSTER_COLOR = colors.text;
