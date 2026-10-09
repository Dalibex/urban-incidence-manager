# ADR-0008: Use Spring Boot for the backend

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

The web and mobile clients need an API to manage users, incidents, evidences and locations, and to
resolve map data coming from OpenStreetMap. The backend owns the data stored in PostgreSQL
([ADR-0001](0001-database.md)), including geo-spatial queries handled by PostGIS
([ADR-0003](0003-geospatial-data-management.md)), and it is also the component that has to cache
third-party map responses behind a dedicated cache layer (see [ADR-0012](0012-cache-redis.md)).

The clients are written in TypeScript and share domain logic with each other
([ADR-0007](0007-client-application-architecture.md)), but the backend has different requirements:
transactions, a solid persistence layer, background jobs for cache refresh.

Which technology should be used for the backend?

## Decision

We will use **Java with Spring Boot**, exposing a **RESTFUL API** over JSON.

- **Data access with JPA (Hibernate) plus native queries** for PostGIS operations, over the same
  PostgreSQL instance.
- **Spring Security** for authentication, implementing the token strategy of
  [ADR-0004](0004-authentication-strategy.md).
- **Contract first with OpenAPI:** the specification is the source of truth and the TypeScript
  types of the shared `api-client` package are generated from it
  ([ADR-0007](0007-client-application-architecture.md)).
- **OSM access behind a caching layer:** Nominatim and Overpass responses are served from Redis
  with a TTL (Time to Live), and refreshes run as scheduled background jobs, so usage policies and rate limits are
  respected ([ADR-0002](0002-open-street-map.md), [ADR-0012](0012-cache-redis.md)).
- **Deployed as a containerized service**, without serverless or stateful infrastructure beyond the
  database.

## Considered Options

- **Java with Spring Boot** — Mature, transactional and with a strong persistence ecosystem. Selected.
- **Node.js with TypeScript (Fastify or NestJS)** — Same language as the clients and lightweight, but
  less suitable for the transactional and background processing workload.
- **Python with FastAPI** — Productive for data work, but a third language and less mature enterprise
  tooling for this kind of service.

## Consequences

- **Positives:**

  - Transactions, connection pooling and schema migrations are handled by well known libraries.
  - Strong typing and static analysis catch a large share of errors before running.
  - Spring Security, validation and testing support reduce the amount of infrastructure code.
  - Scheduled jobs for cache refresh come out of the box.
  - Durable data lives in PostgreSQL and PostGIS, while ephemeral map responses are served from
    Redis, so disposable cache rows never grow the relational database
    ([ADR-0012](0012-cache-redis.md)).
- **Negatives:**

  - Java is a different language from the TypeScript clients, so code cannot be shared directly; the
    API contract must be maintained through OpenAPI and generated clients.
  - Higher memory and startup footprint than a lightweight Node.js service.
  - The JVM requires attention to container memory limits and build times.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0002: Use OpenStreetMap for map data](0002-open-street-map.md)
- [ADR-0003: Use PostGIS for geo-spatial data](0003-geospatial-data-management.md)
- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0007: Client application architecture](0007-client-application-architecture.md)
- [ADR-0012: Use Redis as cache layer](0012-cache-redis.md)
