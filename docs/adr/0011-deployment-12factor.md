# ADR-0011: Deploy with docker-compose and 12-factor configuration

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-06

## Context

Since the project arises as a cloud platform and its amied to be developed following the 12-factor metodology, it derives several non-functional requirements:
 - Portability between environments without code changes.
 - Stateless application processes.
 - Configuration injected as environment variables.
 - Logs to standard output with central collection.
 - The database is reachable only from the internal network.
 - The system must have a fast start and stop.

However, some services are stateful by nature: PostgreSQL with PostGIS must be available from the first iteration (its late decision is itself a high-severity risk), and other stateful pieces are decided separately, for example the cache.

Two practical questions also need an answer: which of the containers proposed in the C4 Model run in compose
(mobile app is a C4 container but runs on the user's phone), and whether different
environments may use different images (for example an Alpine database image in one environment
and a Debian one in another).

How should the system be deployed and configured in this phase of the project?

## Decision

We will deploy the system as **containers orchestrated by a single `docker-compose` file** on
one machine, keeping every 12-factor practice that makes the same image runnable elsewhere.

  | Service | Image | Exposed |
  |---|---|---|
  | `backend` | Own image built from `backend/Dockerfile` | `BACKEND_PORT` |
  | `db` | `postgis/postgis` (PostgreSQL + PostGIS) | No (internal network) |

- **One image per service for every environment (12-factor X).** The same pinned image tag is
  used in local development, CI (Testcontainers and smoke test), staging and production. Images
  are never chosen per environment. Tags are pinned to a version (never `latest`) and defined once
  in `.env.example`.
- **Debian-based database image.** The database uses the Debian variant of `postgis/postgis`, not
  Alpine. Alpine images use musl libc, whose limited locale support can change how PostgreSQL
  sorts and compares text, which matters for Spanish data (accents, ñ). Image size is irrelevant for
  a stateful service that is pulled once. Alpine is acceptable for stateless images such as nginx.
- **Stateless application process:** sessions and tokens live in the clients and, where
  server-side state is unavoidable, in the cache; the app container can be stopped and started
  at any time.
- **Database on the internal network only:** the `internal` compose network has no published
  ports and no outbound internet access. Only `web` and `backend` publish ports.
- **Local development exception:** `compose.yaml` publishes `db` `127.0.0.1`
  only, so the backend can run from the IDE. It is enabled per developer with
  `COMPOSE_FILE=compose.yaml:compose.dev.yaml` in their local `.env` and is never used on a server.
- **Logs to standard output and standard error** in a structured format; collection is a
  concern of the runtime, not of the application code.
- **Processes as containers:** any background task (cache refresh, retries) runs as a separate
  container or a separate process from the same image, started and stopped independently
  (12-factor processes).
- **Cloud-ready, not cloud-bound:** nothing in the image depends on the machine it runs on, so
  moving the same compose file to a single cloud VM, or replacing compose with an orchestrator
  later, does not require a code change.

## Considered Options

- **docker-compose on one machine** — Reproducible, cheap, matches the risk mitigation and the
  semester budget. Selected.
- **Managed cloud services (managed database, container service or Kubernetes)** — recurring cost and a large learning surface for a first delivery whose main risk is scope, not infrastructure.
- **Jar file plus systemd on a VM** — The simplest possible runtime, but environments drift
  because the database, PostGIS and the cache must still be installed by hand on each machine,
  which breaks portability.
- **Platform as a Service** — Minimal operations, but vendor lock-in in the deployment
  descriptor and cost per running service; it also hides the 12-factor behaviours the project
  has to demonstrate.

For the database image:

- **Debian-based `postgis/postgis`, same tag everywhere**.
- **Alpine-based image everywhere** — Smaller, but musl locale behaviour can change text ordering
  and comparison.
- **Different images per environment** — Rejected: tests would run against something different
  from what is deployed, which is exactly what 12-factor X forbids.

docker-compose was selected because it gives a reproducible environment for every developer,
keeps cost at zero for the demo, and still exercises every 12-factor practice that the
requirements demand.

## Consequences

- **Positives:**
  - One command brings up the whole system, including PostGIS and the cache, on any machine.
  - Portability is proven by construction: the same image and environment variables work
    locally, in CI and on a server.
  - The database never exposes a port to the outside, and secrets stay out of the repository.
  - Text ordering and comparison behave the same in every environment.
  - Logs can be collected centrally without touching the code.
  - Migration to a cloud VM or an orchestrator later is a deployment change, not a code change.
- **Negatives:**
  - Backups, updates and monitoring are manual responsibilities of the team.
  - Compose does not provide load balancing; horizontal scaling would need an orchestrator.
  - Local and demo behaviour can hide problems that only appear with load balancing or
    replicated services, so those remain explicitly out of scope for now.
  - The mobile app is not covered by compose; testing it against a local backend requires further steps.
  - The `postgis/postgis` image may not provide an arm64 variant; on Apple Silicon machines it may
    run emulated and must be checked.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0003: Use PostGIS for geo-spatial data](0003-geospatial-data-management.md)
- [ADR-0005: Use React Native for the mobile application](0005-mobile-application-technology.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0010: Start with a modular monolith](0010-modular-monolith.md)
- `compose.yaml`, `compose.dev.yaml`, `.env.example`
- C4 model: `docs/architecture/workspace.dsl` (deployment view "Despliegue")
