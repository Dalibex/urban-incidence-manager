# ADR-0003: Use PostGIS for geo-spatial data

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-09-29

## Context

Incidents and their evidences carry a location, and map features are resolved against
OpenStreetMap data. Users need to filter and aggregate incidents by area ("incidents in this
district", "evidents within X meters of this point").

How should geo-spatial data be stored and queried?

## Decision

We will use the **PostGIS** extension on top of PostgreSQL for all geo-spatial data.

- Store locations as `geography(Point, 4326)` so distances are expressed in meters.
- Index geometry columns so spatial filtering uses the index.
- Keep spatial predicates in the database instead of filtering in application code.

## Considered Options

- **PostGIS** — Spatial types, indexes and functions inside PostgreSQL. Selected.
- **Latitude/longitude columns with plain B-tree indexes** — No spatial operators; bounding-box
  filtering becomes manual and error prone.
- **External geo-spatial service (e.g. Elasticsearch or a tile service)** — Powerful, but adds
  another stateful system and a data duplication/consistency problem.

PostGIS was selected because it keeps geo-spatial data in the same database as the rest of the
model, so spatial filters compose with regular SQL and no second system has to be synchronized.

## Consequences

- **Positives:**
  - Distance, bounding-box and containment queries are available as standard SQL.
  - Spatial indexes keep those queries fast as the data grows.
  - Spatial filters can be combined with joins and aggregations on incidents and evidences.
  - One database to back up, migrate and maintain.
- **Negatives:**
  - The extension must be available in every environment, including local development and CI.
  - Geometry types and functions have to be handled by the ORM, migrations and serializers.
  - Adding, upgrading or removing the extension is a change that affects database setup scripts.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0002: Use OpenStreetMap](0002-open-street-map.md)
