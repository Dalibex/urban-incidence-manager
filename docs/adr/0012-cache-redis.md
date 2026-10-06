# ADR-0012: Use Redis as cache layer

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-06

## Context

Several flows repeat expensive or rate-limited work:

- Geocoding and map feature lookups go to external OpenStreetMap endpoints (Nominatim, Overpass)
  whose public usage policies are strict and whose availability is not guaranteed
  ([ADR-0002](0002-open-street-map.md)).
- The same enrichment (district, urban asset, nearby context) can be requested repeatedly for
  incidents in the same area ([ADR-0009](0009-urban-data-source.md)), and the platform requires
  that external sources never block a report, which means cached values must be readable
  instantly.
- Map viewport queries and filtered incident lists are read constantly and are the main
  efficiency requirement once the data volume grows.
- Rate limiting on login and on external calls needs a shared counter across restarts
  ([ADR-0004](0004-authentication-strategy.md)).

The backend decision (see [ADR-0008](0008-backend.md)) puts external calls behind a caching
layer, but it does not decide where that cache lives. Storing cache entries **in PostgreSQL with
a TTL** would work, but it mixes ephemeral cache entries with durable data, it grows the
relational database with disposable rows, and it does not serve hot reads of computed results.

Where should ephemeral, reusable values be cached?

## Decision

We will use **Redis as a dedicated cache layer** with time-to-live on every entry, in front of
PostgreSQL and in front of external data sources.

- **What is cached:** external responses (geocoding, Overpass, opendata lookups), resolved
  geodata per zone (district, assets), computed aggregates for the dashboard and map viewports,
  and rate-limit counters.
- **Pattern:** cache-aside. The backend reads the cache first; on a miss it computes or fetches,
  stores the result with a TTL and returns it. Entries expire by TTL, so there is no manual
  invalidation protocol to get wrong.
- **Graceful degradation:** if Redis is unavailable, requests fall through to PostgreSQL or to
  the (bounded, retried) external call. A cache outage must never fail a request, and it must
  never block the creation of an incident.
- **Durability stays in PostgreSQL:** provenance-bearing data (observations with source,
  observation instant, ingest instant, quality and expiry) is persisted in the relational
  database as in [ADR-0001](0001-database.md). Redis stores only derived, expirable values and
  is safe to lose entirely.
- **Relationship with ADR-0008:** this decision **is the caching layer referenced by
  [ADR-0008](0008-backend.md)**: every external response that ADR-0008 puts "behind a caching
  layer" is stored in Redis, and PostgreSQL keeps only durable, provenance-bearing data. The
  backend's responsibilities (timeouts, retries, scheduled refresh jobs) are unchanged.

## Considered Options

- **No cache** — Simplest, but every map render or incident list would hit PostgreSQL and every
  enrichment would hit external rate-limited endpoints; unacceptable for query efficiency under
  data volume and for the OSM usage policy.
- **In-process cache (e.g. Caffeine)** — No extra service and very fast, but each instance has
  its own copy, entries are lost on every restart, and counters are not shared.
- **PostgreSQL with TTL columns** — No new dependency, but
  ephemeral rows pollute the relational database, reads still pay SQL overhead for hot keys, and
  expiring rows must be garbage-collected.
- **Redis as an external cache service** — Selected. Shared across instances, TTL is a native
  feature, sub-millisecond reads for hot keys, and it also provides atomic counters for rate
  limiting.

## Consequences

- **Positives:**
  - External OSM and opendata calls are absorbed by the cache, respecting usage policies and
    protecting the rule that the report path never blocks on external data.
  - Hot reads (map viewports, lists, dashboard aggregates) are served without touching
    PostgreSQL, which keeps query latency low as volume grows.
  - TTL is native, so expiry and staleness bounds are expressed once per key instead of being
    implemented ad hoc.
  - Rate limiting gets atomic counters without new table writes.
  - PostgreSQL is left with durable data only, which keeps backups smaller and the schema
    cleaner.
- **Negatives:**
  - One more stateful service to run, back up and monitor in the compose deployment
    ([ADR-0011](0011-deployment-12factor.md)); its memory must be sized and watched.
  - Cache invalidation is only "expire after TTL", so a stale value can be served for the
    lifetime of its key; TTLs must be chosen per data type.
  - Two places to reason about when debugging a wrong value: is it stale in Redis, or wrong in
    PostgreSQL.
  - If the cache is used as a shortcut for data that actually needs provenance, traceability is
    lost; the rule "durable data in PostgreSQL, derived data in Redis" has to be enforced in
    review.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0002: Use OpenStreetMap for map data](0002-open-street-map.md)
- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0009: Use opendata de Málaga as primary urban data source](0009-urban-data-source.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
