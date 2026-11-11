/**
 * Where the base map style is served by default: the web's own origin, from the Vite development
 * server in development and from nginx in a deployment (ADR-0016, docs/deployment.md). So no
 * request goes to an external tile host.
 */
export const DEFAULT_MAP_STYLE_URL = '/tiles/style.json';

/**
 * The absolute URL of the map style: `VITE_MAP_STYLE_URL` or {@link DEFAULT_MAP_STYLE_URL}. A
 * relative value is resolved against `base` (the page), because MapLibre loads the style with an
 * absolute URL.
 */
export function mapStyleUrl(base: string = window.location.href): string {
  const configured = import.meta.env.VITE_MAP_STYLE_URL?.trim();
  return new URL(configured || DEFAULT_MAP_STYLE_URL, base).href;
}

/** Centre of Málaga (WGS 84), where the map opens, and the zoom that shows the whole city. */
export const MALAGA_VIEW = { longitude: -4.42, latitude: 36.72, zoom: 12 } as const;

/**
 * The zoom a selected incident is shown at, near enough to see its street. Above the last zoom
 * that clusters, so the incident shows as its own marker.
 */
export const INCIDENT_ZOOM = 16;

/** The last zoom with clusters; from the next one every incident is its own marker. */
export const CLUSTER_MAX_ZOOM = 15;

/**
 * The id of the block of the map and its side column. The full-screen button of the map takes the
 * whole block, not the map alone, so a summary opened in full screen is still seen (#177).
 */
export const MAP_WORKSPACE_ID = 'map-workspace';
