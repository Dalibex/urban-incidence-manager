# ADR-0009: Use OpenData from Málaga as primary urban data source with OSM fallback

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

Several capabilities of the platform need urban data that our users do not create: identifying
the neighborhood and district of an incident, associating it with the nearest urban asset,
fetching external contextual data, showing that context to operators and correlating incidents
with historical urban variables. The project scope states that external data comes from the
opendata portal of the "Ayuntamiento de Málaga" ([Ciudad de Málaga](https://www.malaga.eu//)).

That portal is an external dependency we do not control, and its actual catalogue is unknown:
it may not publish a geospatial inventory of urban assets (traffic lights, containers, bus
stops) nor district polygons as geometry. Without polygons, point-in-polygon district
assignment is impossible; without an asset inventory, there is no data to associate. In
addition, the specification is explicit that external data enriches the incident and must never
block its creation, and that every observation must keep its provenance, observation time,
ingest time, unit and quality.

Which source(s) of urban data should we use, and what happens when a source is incomplete or
unavailable?

## Decision

We will use **the opendata portal of the Ayuntamiento de Málaga as the primary source of urban
data**, with **OpenStreetMap as a documented fallback** for whatever the portal does not cover.

- **Primary source:** OpenData for districts, neighborhoods, equipment and any other
  municipal dataset the catalogue actually offers. It is the authoritative source for the city
  of Málaga and it is the source stated in the project scope.
- **Fallback source:** OSM (Overpass, already used for map features in
  [ADR-0002](0002-open-street-map.md)) for urban assets and, if the portal has no district
  geometry, for district centroids.
- **District assignment:** point-in-polygon with PostGIS when geometry is available; otherwise
  centroid plus minimum distance, and the loss of accuracy is recorded as a known limitation of
  this decision (see [ADR-0003](0003-geospatial-data-management.md)).
- **Never blocks the report:** every enrichment (district, asset, context) runs asynchronously
  after the incident is created. If a source is down, the incident is still created and the
  dependent fields are filled in later or marked as unavailable.
- **Audit the catalogue first:** the real catalogue of OpenData is verified before
  committing to district, asset and context features; the outcome of that audit decides which sourceis used in practice.

## Considered Options

- **Opendata de Málaga only** — One authoritative source and nothing else, but if the catalogue
  lacks assets or polygons, district and asset features simply have no data and the map loses
  value.
- **OpenStreetMap only** — Complete and free, but ignores the municipal authority of the
  opendata portal and the project scope, and would throw away better data where it exists.
- **OpenData de Málaga primary, OSM fallback** — Selected. The authoritative source is used
  when it covers the need, and the gap is filled from a source we already consume for maps, with
  the accuracy loss explicitly documented instead of hidden.

## Consequences

- **Positives:**
  - District, asset, context and historical-correlation features have a data source even if the
    municipal portal is incomplete, which defuses the highest-severity technical risk of the
    project.
  - The fallback reuses the OSM knowledge already required by
    [ADR-0002](0002-open-street-map.md), so no third integration is introduced.
  - Asynchronous enrichment guarantees that no external failure ever blocks a citizen report.
  - Recording source and quality on every observation makes the contextual view explainable and
    reproducible.
- **Negatives:**
  - Two sources means two integration formats and, for the same real-world object, potentially
    two different values; the source of each value must be visible to the operator.
  - The centroid plus minimum distance path for districts is less accurate than point-in-polygon
    and that degradation is permanent while the portal has no geometry.
  - Each source needs its own timeout, retry and cache policy (see
    [ADR-0012](0012-cache-redis.md) and [ADR-0008](0008-backend.md)).
  - The decision stays partially unverified until the catalogue audit is done; if both sources
    fail to cover an asset type, asset association must be re-scoped with a new ADR.

## Links Related

- [ADR-0002: Use OpenStreetMap for map data](0002-open-street-map.md)
- [ADR-0003: Use PostGIS for geo-spatial data](0003-geospatial-data-management.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0012: Use Redis as cache layer](0012-cache-redis.md)
