import type { components } from '@urbanpulse/api-client';
import type { IncidentStatus } from '@urbanpulse/domain';
import { colors, statusColor } from '@urbanpulse/ui';

type IncidentPosition = components['schemas']['IncidentPosition'];
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
): IncidentFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: positions.map((position) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [position.longitude, position.latitude] },
      properties: {
        id: position.id,
        status: position.status,
        priorityRank: priorityRank(position.priority),
        color: statusColor(position.status),
      },
    })),
  };
}

/**
 * The color of a cluster by the highest priority inside it, from none to `HIGH`. The legend of the
 * map reads the same table, so both never disagree.
 */
export const CLUSTER_PRIORITY_COLORS: readonly { rank: number; label: string; color: string }[] = [
  { rank: 0, label: 'No priority', color: colors.textMuted },
  { rank: PRIORITY_RANK.LOW, label: 'Low', color: colors.primary },
  { rank: PRIORITY_RANK.MEDIUM, label: 'Medium', color: colors.warning },
  { rank: PRIORITY_RANK.HIGH, label: 'High', color: colors.danger },
];
