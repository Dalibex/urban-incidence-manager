# ADR-0011: Deploy with docker-compose and 12-factor configuration

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-06

## Context

The project declares itself as a cloud platform, and the non-functional requirements derived
from the 12-factor guide demand portability between environments without code changes, stateless
application processes, configuration injected as environment variables, logs to standard output
with central collection, a database reachable only from the internal network and fast start and
stop.

At the same time, the risk analysis rates the cost of infrastructure (managed database plus
storage plus LLM on a single semester) as a medium risk whose mitigation is "todo en
docker-compose local y una sola máquina si la nube es obligatoria". Some services are stateful
by nature: PostgreSQL with PostGIS must be available from the first iteration (its late
decision is itself a high-severity risk), and other stateful pieces are decided separately, for
example the cache ([ADR-0012](0012-cache-redis.md)).

How should the system be deployed and configured in this phase of the project?

## Decision

We will deploy the system as **containers orchestrated by a single `docker-compose` file** on
one machine, keeping every 12-factor practice that makes the same image runnable elsewhere.

- **One compose file** with the application container
  ([ADR-0008](0008-backend.md), [ADR-0010](0010-modular-monolith.md)), PostgreSQL with PostGIS
  ([ADR-0001](0001-database.md), [ADR-0003](0003-geospatial-data-management.md)) and any other
  stateful service the project adopts, each declared as its own service with a health check.
- **Configuration only through environment variables** (and `.env` files that are not committed):
  URLs, credentials, ports, feature flags and timeouts. No configuration compiled into the
  image, so the same image runs on any machine.
- **Stateless application process:** sessions and tokens live in the clients and, where
  server-side state is unavoidable, in the cache; the app container can be stopped and started
  at any time.
- **Database on the internal network only:** the compose network does not publish the
  PostgreSQL port to the host; only the application port is exposed.
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
- **Managed cloud services (managed database, container service or Kubernetes)** — Best
  availability and operations, but recurring cost and a large learning surface for a first
  delivery whose main risk is scope, not infrastructure.
- **Jar file plus systemd on a VM** — The simplest possible runtime, but environments drift
  because the database, PostGIS and the cache must still be installed by hand on each machine,
  which breaks portability.
- **Platform as a Service** — Minimal operations, but vendor lock-in in the deployment
  descriptor and cost per running service; it also hides the 12-factor behaviours the project
  has to demonstrate.

docker-compose was selected because it gives a reproducible environment for every developer,
keeps cost at zero for the demo, and still exercises every 12-factor practice that the
requirements demand.

## Consequences

- **Positives:**
  - One command brings up the whole system, including PostGIS and the cache, on any machine.
  - Portability is proven by construction: the same image and environment variables work
    locally and on a server.
  - The database never exposes a port to the outside, and secrets stay out of the repository.
  - Logs go to stdout and can be collected centrally without touching the code.
  - Migration to a cloud VM or an orchestrator later is a deployment change, not a code change.
- **Negatives:**
  - One machine is a single point of failure: no automatic failover, and availability depends on
    that host.
  - Backups, updates and monitoring are manual responsibilities of the team.
  - Compose does not provide scaling; horizontal scaling would need an orchestrator.
  - Local and demo behaviour can hide problems that only appear with load balancing or
    replicated services, so those remain explicitly out of scope for now.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0003: Use PostGIS for geo-spatial data](0003-geospatial-data-management.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0010: Start with a modular monolith](0010-modular-monolith.md)
- [ADR-0012: Use Redis as cache layer](0012-cache-redis.md)
