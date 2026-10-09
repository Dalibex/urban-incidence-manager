# ADR-0001: Use PostgreSQL for primary database

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

Application requires a relational database, as we need to store data about user accounts, incidents and its evidences and location, maps information...

The team also has more experience using this type of database.

This data is highly relational: an incident has many evidences, evidences carry geo-points, and geo-points are resolved against map features coming from OpenStreetMap. That means we need referential integrity, complex queries (for example, "all incidents within an area with at least one evidence") and transactions.

What database should be used initially, considering the current scope and requirements of the project?

## Decision

We will use **PostgreSQL** as primary database for all our data.

- **Relational data model:** incidents, evidences, geo-points and users need tables with foreign keys and constraints.
- **Geo-spatial queries:** incidents and evidences are located, so we need spatial filtering and distance queries.
- **JSONB support:** map responses from OpenStreetMap, which are semi-structured and change over time, can be stored without forcing a schema migration on every upstream change.
- **Mature ecosystem:** widely adopted ORM, migration and tooling support.
- **Cost and complexity:** a single, well-known engine keeps operations and maintenance simple.

## Considered Options

- **PostgreSQL** — Relational. Selected.
- **MySQL** — Relational and widely known, but weaker geo-spatial support and no JSONB equivalent.
- **MongoDB** — Document oriented; would mean embedding evidences in incident documents or manually managing references and consistency.

PostgreSQL was selected because it covers both the relational and the flexible document needs with one engine.

## Consequences

- **Positives:**
  - Complex reporting queries (aggregations per area, per user, over time) are handled with plain SQL.
  - Transactions guarantee that an incident and its evidences are saved atomically.
  - JSONB columns allow storing evolving OpenStreetMap payloads without schema changes.
  - Spatial needs can be covered by an extension without changing the chosen engine (see [ADR-0003](0003-geospatial-data-management.md)).
- **Negatives:**
  - Requires hosting and operating a stateful service (backups, upgrades, monitoring) instead of using a serverless document store.
  - Horizontal write scaling is limited compared to document databases, so sharding would be needed if write volume grows significantly.
  - Spatial indexing, geometry types and the extension itself must be maintained as part of the database (see [ADR-0003](0003-geospatial-data-management.md)).
  - Badly designed indexes or queries can degrade performance, so query plans need to be reviewed as the data grows.

## Links Related

- [ADR-0002: Use OpenStreetMap](0002-open-street-map.md)
- [ADR-0003: Use PostGIS for geo-spatial data](0003-geospatial-data-management.md)
