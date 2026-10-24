# ADR-0016: Self-Hosted OSM Base Map Refreshed Every Two Weeks

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-21

## Context

RF08 requires showing incidents and urban layers on a map, on the web app (staff) and on the
mobile app (citizens and technicians). [ADR-0002](0002-open-street-map.md) chose OpenStreetMap as
the map data source to avoid API keys and vendor lock-in.

The current plan does not hold up for the mobile app (open decision D3, UP-117):

- **`react-native-maps`** needs a Google Maps API key on Android to publish the app, which
  contradicts ADR-0002 and requires a Google account with billing.
- **The public OSM tile servers** (`tile.openstreetmap.org`) need no key, but their usage policy
  forbids heavy use such as distributing an app that relies on them without permission, does
  not allow offline use or tile archives, and access can be blocked without notice. They run on
  donated resources and are not meant to back a product.
- **Commercial OSM-based providers** (MapTiler, Stadia Maps, etc.) bring back an API key, an
  account and usage limits.

At the same time, the base map does not need to be live. The data that changes often
(incidents, assets, urban context) comes from our own API and is drawn on top of the base map.
Streets, buildings and place names in the Málaga area change slowly.

**Question:** How can the web and mobile apps show an OSM base map without an API key, without
depending on the public OSM tile servers, and with data that stays reasonably up to date?

## Decision

We will **generate our own OSM vector tiles for the Málaga area every two weeks and serve them
ourselves**, with MapLibre as the map renderer on web and mobile.

- **Data source.** The regional OSM extract from Geofabrik (Andalucía, `.osm.pbf`), clipped to
  the bounding box of the province of Málaga.
- **Tile generation.** [Planetiler](https://github.com/onthegomap/planetiler) with its built-in
  OpenMapTiles profile, run from its Docker image, produces a single **PMTiles** file
  (`malaga.pmtiles`, zoom 0–14). No PostGIS import and no tile-rendering server are needed.
- **Refresh every two weeks.** A one-off admin process (Twelve-Factor XII), a Compose service
  `tiles-builder` under a `tiles` profile, downloads the extract and rebuilds the file. It is
  triggered every 14 days by the scheduler chosen in UP-147 (D7); until then, a host cron entry
  runs `docker compose --profile tiles run --rm tiles-builder`. The job can also be run by hand.
- **Safe replacement.** The new file is written to a temporary name and renamed only when the
  build succeeds and passes a basic check (`pmtiles verify`, minimum size). If anything fails,
  the previous file keeps being served and the job exits with an error that shows in the logs.
- **Serving.** The `web` service (nginx, [ADR-0011](0011-deployment-12factor.md) and
  [ADR-0017](0017-reverse-proxy.md), the latter still Proposed) serves `malaga.pmtiles` from a
  named volume at `/tiles/`, with HTTP range requests and cache headers.
  The map style (an OpenMapTiles-compatible style such as OSM Bright or Positron), its fonts
  (glyphs) and its sprites are served from the same place, so the style references no external
  host that could ask for a key. When the evidence storage of UP-116 exists, the file may move
  there by changing only the configured URL.
- **Clients.** The style URL comes from configuration (`VITE_MAP_STYLE_URL`,
  `EXPO_PUBLIC_MAP_STYLE_URL`):
  - Mobile: `@maplibre/maplibre-react-native` with its Expo config plugin, reading the PMTiles
    file through the `pmtiles://` protocol. It needs a development build (not Expo Go).
  - Web: MapLibre GL JS (`react-map-gl/maplibre`), already chosen in
    [ADR-0006](0006-frontend-web-technology.md), with the `pmtiles` protocol, so both apps use the
    same tiles and style.
- **Attribution.** Both apps show "© OpenMapTiles © OpenStreetMap contributors" on the map, as
  required by the ODbL licence.
- **Out of scope.** Geocoding and reverse geocoding (Nominatim, ADR-0002, UP-051) and the urban
  layers drawn on top of the base map (RF08, RF22) are not covered by this ADR.

**Drivers:**

- No API key and no account with any map provider (ADR-0002).
- Compliance with the OSM tile usage policy.
- Same base map and style on web and mobile.
- Simplest option that works: one file, one job every two weeks, no extra running service.
- Zero cost, everything inside Compose (ADR-0011).

**Hypothesis and evaluation:**

- If we serve our own PMTiles file rebuilt every two weeks, both apps can show the base map with
  no API key, with data at most two weeks old, and with performance as good as a hosted
  provider.
- Metrics: smoothness of the UP-117 prototype (100 markers, Android and iOS); size of
  `malaga.pmtiles`; build time; age of the served file.
- Thresholds: smooth on a mid-range phone; file under 200 MB; build under 15 minutes on the
  deployment VM; served file never older than 21 days (one missed run is tolerated).
- Review: Sprint 3 retrospective. Revert to a hosted provider if the build cannot run reliably on
  the VM or the size or performance thresholds are not met.

## Considered Options

- **`react-native-maps` with a Google API key** — Most widely used and documented by Expo, but
  contradicts ADR-0002 and needs a Google account with billing.
- **Commercial OSM tile provider (MapTiler, Stadia Maps)** — Nothing to run and good styles, but
  needs an API key, an account and depends on a free tier that can change.
- **Public `tile.openstreetmap.org` tiles** — No key, but distributing an app that uses them is
  forbidden by the usage policy without permission, offline use is not allowed, and access can
  be blocked at any time.
- **Self-hosted PMTiles rebuilt every two weeks** — No key, no policy issue, same tiles on web
  and mobile, and no extra running service. Selected.
- **Full tile server with live updates (osm2pgsql + Martin or renderd, minutely diffs)** —
  Always up to date, but adds a large OSM import, a tile server and continuous updates that the
  project does not need.
- **Keep the current situation (no mobile map)** — RF08 cannot be met on mobile.

A base map refreshed every two weeks is fresh enough for incident management, and a single
PMTiles file is the simplest way to self-host it.

## Consequences

- **Positives:**
  - No API key, account or billing with any map provider; ADR-0002 is respected.
  - No dependency on the public OSM servers or their policy changes.
  - The same tiles, style and renderer on web and mobile.
  - Vector tiles: smooth zoom, styles we control, smaller downloads than raster tiles.
  - PMTiles is a single static file: nothing to run besides nginx, and it can move to object
    storage later without code changes.
- **Negatives:**
  - The base map can be up to two weeks old.
  - The team owns a scheduled job, a style, fonts and sprites that need maintenance.
  - The build needs RAM and disk on the VM for a few minutes every two weeks.
  - The mobile app needs a development build instead of Expo Go.
  - The web map loads the self-hosted tiles instead of public OSM tiles; UP-052 must be updated.
  - Only the Málaga area is covered; the map is empty outside the bounding box.

## Links Related

- UP-117 (mobile map component without an API key), open decision D3
- [ADR-0002](0002-open-street-map.md) (OpenStreetMap for map data)
- [ADR-0005](0005-mobile-application-technology.md) (React Native for the mobile app)
- [ADR-0006](0006-frontend-web-technology.md) (React for the web app)
- [ADR-0011](0011-deployment-12factor.md) (Docker Compose and 12-factor configuration)
- [ADR-0012](0012-cache-redis.md) (Redis Cache)
- C4 model: `web` container also serves the base map tiles; new admin process `tiles-builder`
- OSM tile usage policy: https://operations.osmfoundation.org/policies/tiles/
- Planetiler: https://github.com/onthegomap/planetiler
- MapLibre React Native: https://maplibre.org/maplibre-react-native/
