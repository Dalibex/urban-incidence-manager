# ADR-0002: Use OpenStreetMap for map data

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

The application displays incidents and evidences on a map, resolves addresses typed by users and
needs map features (roads, buildings, water, administrative boundaries) to give context to a
location.

Any map solution requires a provider, which implies cost, an API key, and a dependency on a
commercial vendor. Incidents may also fall outside the areas covered by commercial providers, and
the data is also useful for another project, so reusing the same source avoids duplicating work.

Which source of map data should we use?

## Decision

We will use **OpenStreetMap (OSM)** data as the source of map features and geocoding.

- Use **Nominatim** for geocoding and reverse geocoding of user-entered addresses.
- Use **Overpass API** to fetch map features around incident locations (roads, buildings, POIs).
- Use OSM-compatible tiles for rendering the map.
- **No API key and no vendor lock-in:** the data is open and any provider or self-hosted tile
  server can be swapped without touching the domain model.
- **Main source for this and other projects:** shared knowledge of the data model and of the API
  usage across projects reduces the learning curve and duplicated effort.

## Considered Options

- **OpenStreetMap (Nominatim / Overpass / OSM tiles)** — Open data, no keys, self-hostable. Selected.
- **Google Maps Platform** — Excellent coverage and geocoding quality, but requires billing, an API
  key and proprietary terms; usage is metered per request.

OpenStreetMap was selected because it provides the required map data without cost or vendor
lock-in, and because it is going to be the main source for more than one project, so the
accumulated knowledge of its APIs will pay off.

## Consequences

- **Positives:**
  - No licensing cost, no billing quotas and no per-request metering.
  - No API key management, and no exposure of a provider key in the client.
  - Data can be self-hosted or mirrored, giving control over availability and caching.
  - Detailed map data.
- **Negatives:**
  - Free public endpoints (Nominatim, Overpass) have strict usage policies and rate limits, so the
    app must cache aggressively and must not issue per-user requests directly.
  - Public endpoints offer no uptime guarantee; production use may require self-hosted instances or
    a commercial tile provider.
  - Overpass queries can be slow or time out on broad areas, so queries must be bounded.
  - Results may be less accurate or less complete than commercial providers in some areas, and
    results must be cached to respect the usage policy.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0003: Use PostGIS for geo-spatial data](0003-geospatial-data-management.md)
- [ADR-0005: Mobile Application Technology](0005-mobile-application-technology.md)
- [ADR-0012: Redis Cache](0012-cache-redis.md)
