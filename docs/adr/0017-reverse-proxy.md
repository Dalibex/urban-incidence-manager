# ADR-0017: nginx as the single entry point in the Docker deployment

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-11-02

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

In the **production/demo compose** we use **nginx as the unique published entry point**:

- nginx serves the static build of the web app (Vite output) and proxies `location /api/` to the backend container; the SPA fallback and the long-lived asset cache live in the same file.
- We prefer an **unprivileged nginx image** (for example `nginxinc/nginx-unprivileged` listening on 8080) to follow the "unprivileged in the container" practice.
- The `nginx.conf` maximum request body size (`client_max_body_size`) must match the evidence upload limit defined in ADR-0013.
- Only the `web` container publishes a host port (`HTTP_PORT`). The `backend`, `db` and Redis publish no ports, per ADR-0011.
- nginx tells the backend who the client is and which address it used: it writes `X-Forwarded-For` (its own `$remote_addr`), `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Port` itself, at the `server` level of `nginx.conf`, so a client's own values never pass, and removes the client's `Forwarded` and `X-Forwarded-Prefix`. The backend trusts them only in the deployment Compose (`SERVER_FORWARD_HEADERS_STRATEGY=native`) and only from a private address (#200).
- **HTTPS is strictly required** before any deployment reachable from the internet, for three reasons:
  - credentials and tokens must not travel in plain HTTP;
  - browsers only allow geolocation and the camera on secure origins;
  - iOS and Android block plain HTTP from release builds.
- TLS terminates at nginx. How the certificate is obtained will be decided alongside the cloud deployment.
- The same origin in production means **no CORS configuration is needed** and no extra headers or preflight rules have to be maintained.

In the **development compose** we keep direct ports (Vite on 5173, API on 8080) and instruct Vite to proxy `/api` to the backend through `API_PROXY_TARGET`; this avoids CORS in development too and lets the developer open two well-known URLs without another moving part.

The full-stack **`docker-compose.yaml` file is located at the repository root**, because it builds images from both `backend/` and `frontend/`, while development-only database settings remain in `backend/docker-compose.yaml`. The file and profile names match the decisions made in the backend container issue (#90).

## Considered Options

- **No proxy, one published port per service** — Simplest per-service, but exposes more surface,
  forces CORS in the browser, and lets the API drift from the web origin.
- **Spring Boot serving the bundled web** — One process, but couples frontend releases to backend
  releases, inverts our web-first delivery and requires serving static content from the JVM.
- **Caddy or Traefik as reverse proxy** — One binary, automatic TLS and nice dashboards, but adds a second image and a learning surface not justified when nginx is already needed to serve the static build.
- **nginx from an unprivileged image** — Selected for production: already needed to serve the static
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
  - One more configuration artifact to keep in sync with the API path (`/api`) and the evidence upload size limits.
  - Development and production expose ports differently (documented).
  - Behind nginx the backend sees nginx as the client of every request, so the rate limits per client address would be shared by everyone and the `Location` of a `201` would point inside Compose. The forwarded headers above mitigate it (#200), and they are only safe while the backend publishes no port.

## How it will be evaluated

- **Metric:** Ports published by the deployed Compose file and CORS configuration needed by the web client.
- **Threshold:** Exactly one published port (`web`), and zero CORS configuration in the backend.
- **Review date:** Before the AWS deployment. A move to a managed load balancer (for example an AWS ALB in front of several services) would need a new ADR.

## Links Related

- [ADR-0006: Use React for the web application](0006-frontend-web-technology.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0013: Local volume behind a StorageService interface for evidences](0013-evidence-storage.md)
