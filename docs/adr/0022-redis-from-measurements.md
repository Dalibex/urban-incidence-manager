# ADR-0022: Add Redis only when a measurement or a second replica asks for it

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-11-05
- **Supersedes:** [ADR-0012](0012-cache-redis.md)

## Context

[ADR-0012](0012-cache-redis.md) chose Redis as the cache of the platform: external responses,
resolved geodata, dashboard aggregates, map viewports, incident lists and rate-limit counters. Its
implementation status (2026-11-02) added Redis when the first of three things happened: the first
adapter to an external source with a usage limit, a second backend replica, or a measurement that
misses its goal.

Since then the incident queries have been measured on the `perf` dataset (100,000 incidents):

- Every main query of the database takes under 10 ms with its index and no cache (#132, *Indexes*
  in the [database guide](../database.md)).
- The load test of QA-1 (#155: k6, 20 concurrent users for 5 minutes) gives a p95 under 30 ms for
  the list, the detail and the history of every role, against a goal of 500 ms
  ([architecture overview](../architecture.md), QA-1).

Three facts follow:

- The incident list, its detail, its history and the map show live data: a status that changed a
  second ago must show at once. A cache with a time to live would show an old status as current,
  which the domain forbids, and an operator could act on an incident that has already moved on.
- With the first trigger, the first external adapter (Nominatim, for the address search) would
  bring a new stateful service with no evidence that it is needed: a Compose service, its backups, a
  Testcontainers container and its CI image ([CI guide](../ci.md), *A new backing service*). What
  the domain keeps from an external source is stored in PostgreSQL anyway, with its source and
  validity ([ADR-0009](0009-urban-data-source.md)), so the same incident never asks for it twice.
- The rate-limit counters of sign-in and sign-up live in memory, which is correct with the single
  replica of [ADR-0011](0011-deployment-12factor.md). A second replica does need a shared store.

When should Redis enter the platform?

## Decision

PostgreSQL serves every read until a measurement shows that a cache is the fix. Redis enters, or
leaves, according to measurements.

- **Never cached:** the incident list, detail and history, and the live map. When one of them misses
  its goal, the fix starts with its queries and indexes, as #132 did.
- **Redis is added when one of these happens:**
  - a measurement of a quality scenario misses its goal (QA-1, or the map viewport query once #53
    exists) and the measurement shows that caching data that is not live, such as dashboard
    aggregates or resolved geodata per zone, is the fix;
  - the requests to an external provider, measured, go over its usage policy (Nominatim allows one
    per second), with what the domain keeps already stored in PostgreSQL;
  - the backend runs more than one replica: the rate-limit counters move to Redis, because each
    replica would otherwise allow the full limit.
- **An external adapter alone does not add Redis.** Until a trigger happens, it stores what the
  domain keeps in PostgreSQL ([ADR-0009](0009-urban-data-source.md)), with its timeouts and a limit
  on its own requests, and it never blocks a report.
- **When Redis enters, it works as ADR-0012 describes:** cache-aside with a time to live on every
  entry, safe to lose, only derived values (data with provenance stays in PostgreSQL), and an
  outage never fails a request or blocks a report.
- **Redis leaves** when a later measurement shows that the goal is met without it.

## Considered Options

- **Keep the triggers of ADR-0012** — The first external adapter adds Redis. Rejected: a new
  stateful service before any measurement asks for it.
- **Cache the incident lists and the map now** — Rejected: QA-1 is met with a margin of more than
  fifteen times, and the cache would show live data out of date.
- **A measurement decides** — Selected. No service is run without a reason, and the reason is
  written down with its conditions. When a measurement asks for a cache, the options of ADR-0012
  (Redis, an in-process cache, PostgreSQL) are compared again with that measurement.

## Consequences

- **Positives:**
  - One stateful service less to run, back up and monitor on the single host.
  - Live data is always current: no list or map shows a status from before a change.
  - Each cache that enters has a measurement behind it, and its own way to check it still helps.
- **Negatives:**
  - The rate-limit counters stay in memory: a restart resets them (accepted risk in the
    [architecture overview](../architecture.md)).
  - Each external adapter needs a limit on its own requests, and its use of the provider has to be
    measured before it counts as a trigger.
  - [ADR-0008](0008-backend.md) describes external responses served from Redis. That cache now
    arrives only through a trigger of this decision.
  - The C4 model keeps Redis as a decided container that is not built.

## How it will be evaluated

- **Metric:** the load test of QA-1 (*Load test of QA-1* in the [datasets guide](../datasets.md)):
  p95 per operation and role, with the `perf` dataset; the median of each main query with `EXPLAIN
  (ANALYZE, BUFFERS)`; once #53 exists, the map viewport query; and the requests per second to each
  external provider.
- **Threshold:** a p95 under 500 ms with no errors, each query under 10 ms, and no request over a
  provider's usage policy. While they are met, Redis does not enter.
- **Review:** every time QA-1 is measured again (next, on the demo machine), when an external
  adapter is merged, and before a second backend replica.

## Links Related

- [ADR-0012: Use Redis as cache layer](0012-cache-redis.md) (superseded by this decision)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0009: Use OpenData from Málaga as primary urban data source with OSM fallback](0009-urban-data-source.md)
- [ADR-0011: Deploy with docker-compose and 12-factor configuration](0011-deployment-12factor.md)
- [Architecture overview](../architecture.md), QA-1, and the [datasets guide](../datasets.md),
  *Measurements*
- Issue #172 (this decision); measurements in #132 and #155
