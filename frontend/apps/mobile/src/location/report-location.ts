import type { components } from '@urbanpulse/api-client';

/** A WGS 84 position in decimal degrees. */
export interface Coordinates {
  latitude: number;
  longitude: number;
}

/** A position read from the device's GPS, with its accuracy radius in metres when known. */
export interface GpsFix extends Coordinates {
  accuracy: number | null;
}

/**
 * Why the pin is where it is: on the last GPS fix, where the citizen moved it, or on the start
 * point the map opens on without a fix, which nobody chose.
 */
export type PinSource = 'gpsFix' | 'citizen' | 'startPoint';

/**
 * Where the pin in the centre of the map points. Its `source` stays `gpsFix` only while the map is
 * centred on the last GPS fix: once the citizen moves the map, the position is theirs, not the
 * device's.
 */
export interface MapPin extends Coordinates {
  source: PinSource;
}

/** The order MapLibre uses for a point: longitude first, as in GeoJSON. */
export type LngLat = [longitude: number, latitude: number];

/** The order MapLibre uses for a box: [west, south, east, north]. */
export type LngLatBounds = [west: number, south: number, east: number, north: number];

export type ReportedLocation = components['schemas']['ReportedLocation'];

/** Where the map starts when there is no GPS fix. */
export const MALAGA_CENTER: Coordinates = { latitude: 36.72, longitude: -4.42 };

/**
 * The area the self-hosted tiles cover: the `BOUNDS` of `tiles/build-tiles.sh`. The map cannot
 * leave it, so the pin cannot point outside it.
 */
export const COVERED_AREA: LngLatBounds = [-5.62, 36.28, -3.75, 37.3];

/** Whether a position is inside the covered area; its border counts as inside. */
export function isInsideCoveredArea({ latitude, longitude }: Coordinates): boolean {
  const [west, south, east, north] = COVERED_AREA;
  return longitude >= west && longitude <= east && latitude >= south && latitude <= north;
}

export function toLngLat({ latitude, longitude }: Coordinates): LngLat {
  return [longitude, latitude];
}

export function fromLngLat([longitude, latitude]: readonly [number, number]): Coordinates {
  return { latitude, longitude };
}

/**
 * The location sent with a report. With the map on screen it is the pin, once it is on the GPS fix
 * or the citizen moved it: the start point the map opens on without a fix is nobody's position, and
 * sending it would send someone to the wrong place (AGENTS.md section 3, unknown data is not
 * presented as known). The accuracy goes only while the pin is on the GPS fix, because a radius
 * measured by the device says nothing about a position the citizen chose. A GPS fix outside the
 * covered area is not sent while the map is on screen: the map stops the pin on its border, so the
 * citizen would see one place and send another, and the border is nobody's position either (#290).
 * Without the map it is the GPS fix. With nothing known there is nothing to send, and the result is
 * `null`.
 */
export function locationToReport(gps: GpsFix | null, pin: MapPin | null): ReportedLocation | null {
  if (pin?.source === 'citizen') {
    return { latitude: pin.latitude, longitude: pin.longitude };
  }
  if (pin?.source === 'startPoint' || gps === null) {
    return null;
  }
  if (pin !== null && !isInsideCoveredArea(gps)) {
    return null;
  }
  return {
    latitude: gps.latitude,
    longitude: gps.longitude,
    ...(gps.accuracy !== null ? { accuracy: gps.accuracy } : {}),
  };
}
