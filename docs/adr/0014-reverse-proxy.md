# ADR-0014: nginx as the single entry point in the Docker deployment

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-07

## Context

The product has a browser client ([ADR-0006](0006-frontend-web-technology.md)) that today is a
static SPA, plus a REST API ([ADR-0008](0008-backend.md)). The web and the mobile app must call
the API; browsers impose same-origin policies, and the mobile device needs a URL reachable from
its own network. At the same time the deployment rule of
[ADR-0011](0011-deployment-12factor.md) says the compose network must **expose only the
application**, keeping PostgreSQL and the cache on the internal network.

If every service published its own port, the host would open three ports (web, API, database) and
every browser request to the API would need CORS, an extra failure mode for a same-origin
deployment.

How should the system be exposed to the outside?

## Decision

In the **production/demo compose** we use **nginx (official image) as the unique published
entry point**:

- nginx serves the static build of the web app (Vite output) and proxies `location /api/` to the
  backend container; the SPA fallback and the long-lived asset cache live in the same file.
- Only `web` publishes a host port (`HTTP_PORT`, default 8080). The backend, database and Redis
  publish no ports, per ADR-0011.
- The same origin in production means **no CORS configuration is needed** and no extra headers or
  preflight rules have to be maintained.
- TLS termination, if it is ever added, lands on this component without touching the application
  images.

In the **development compose** we keep direct ports (Vite on 5173, API on 8080) and instruct Vite
to proxy `/api` to the backend through `API_PROXY_TARGET`; this avoids CORS in development too and
lets the developer open two well-known URLs without another moving part. This difference is
explicit in `docker-compose.dev.yml`, so it does not leak into production.

## Considered Options

- **No proxy, one published port per service** — Simplest per-service, but exposes more surface,
  forces CORS in the browser, and lets the API drift from the web origin.
- **Spring Boot serving the bundled web** — One process, but couples frontend releases to backend
  releases, inverts our web-first delivery and requires serving static content from the JVM.
- **Caddy or Traefik as reverse proxy** — One binary, automatic TLS and nice dashboards, but adds
  a second image and a learning surface not justified when the compose runs locally with `http`.
- **nginx from the official image** — Selected for production: already needed to serve the static
  build, a single configuration file, mature and one image fewer than the other proxies.

## Consequences

- **Positives:**
  - One public port => one less security surface and no CORS in the browser.
  - The backend and its actuator stay reachable only inside the compose network, which also keeps
    the database completely invisible to the outside.
  - The same nginx config is the future TLS termination point and, later, the gateway for an
    orchestrator migration (ADR-0011, cloud-ready).
  - Production and development behave the same from the browser's point of view (`/api` on the
    same origin).
- **Negatives:**
  - One more configuration artifact to keep in sync with the API path (`/api`) and the evidence
    upload size limits.
  - In development two ports are published (documented), so the single-origin rule is a
    production guarantee, not an automatic one.
  - nginx in the prod image runs with its default user; the container boundary still isolates it,
    but it does not follow the "unprivileged in the container" practice that the backend
    Dockerfile does apply.

## Links Related

- [ADR-0006: Use React for the web application](0006-frontend-web-technology.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0013: Local volume behind a StorageService interface for evidences](0013-evidence-storage.md)
- [Docker guide](../infra/docker/DOCKER.md)
