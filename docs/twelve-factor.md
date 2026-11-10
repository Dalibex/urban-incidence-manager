# 🧩 Twelve-Factor Traceability

How UrbanPulse applies each factor of the [Twelve-Factor App](https://12factor.net/), what would
break it, and how to check it. It describes what the repository shows today; what is not built
says `Planned: #N`. The deployment decision behind most factors is
[ADR-0011](adr/0011-deployment-12factor.md).

| Status | Meaning |
| --- | --- |
| Done | Built, and a CI job or a command shows it |
| Partial | Built for part of the system; the factor says what is missing |
| Planned | Not built; an open issue covers it |
| Accepted limit | Not built, and no issue plans it: the team accepts it for now |

Commands run from `backend/`, with Docker running and `backend/.env` created from
`.env.example` ([development guide](development.md)), unless the factor says otherwise.

## 📋 Summary

| Factor | Status | Issues |
| --- | --- | --- |
| [I. Codebase](#i-codebase) | Done | |
| [II. Dependencies](#ii-dependencies) | Done | |
| [III. Config](#iii-config) | Done | Planned: #200 |
| [IV. Backing services](#iv-backing-services) | Done | |
| [V. Build, release, run](#v-build-release-run) | Partial | Planned: #134, #199 |
| [VI. Processes](#vi-processes) | Partial | Done: #192 |
| [VII. Port binding](#vii-port-binding) | Partial | Planned: #134, #199 |
| [VIII. Concurrency](#viii-concurrency) | Accepted limit | |
| [IX. Disposability](#ix-disposability) | Partial | |
| [X. Dev/prod parity](#x-devprod-parity) | Partial | Done: #90. Planned: #134, #199 |
| [XI. Logs](#xi-logs) | Done | |
| [XII. Admin processes](#xii-admin-processes) | Done | Done: #8, #34, #38, #54, #83, #84, #126 |

## I. Codebase

**Done.** One repository holds the backend, the web and mobile clients, the base map builder and
the documentation. Every change reaches `main` through a pull request (GitHub Flow,
[contributing guide](../CONTRIBUTING.md)), and a release is a tag of `main` (`v0.0.1`, `v0.1.0`).

- **It would break if:** each environment had its own copy of the code, or a long-lived branch.
- **Check:** `git tag` lists the releases, all of them commits of `main`.

## II. Dependencies

**Done.** Every dependency is declared and pinned:

- Backend: `backend/pom.xml` and the Maven Wrapper (`mvnw`), so Maven is not installed.
- Clients: `frontend/pnpm-lock.yaml`, pnpm pinned in `packageManager` of `frontend/package.json`
  and installed by Corepack, Node in `frontend/.nvmrc`.
- Images: pinned by tag in `backend/Dockerfile` (`eclipse-temurin:25.0.4.1_1-*`) and Compose
  (`postgis/postgis:18-3.6`).

Only the toolchain is installed by hand: Java 25, Docker and Node 24 (README, *Prerequisites*).

- **It would break if:** the README asked to install a library, or a build used a version that is
  not in a lockfile.
- **Check:** the `frontend` job installs with `pnpm install --frozen-lockfile`, which fails if
  `package.json` and the lockfile disagree.
- **Limit:** images are pinned by tag, not by digest.

## III. Config

**Done.** Every value that changes between environments is an environment variable:
`application.yaml` reads them, `backend/docker-compose.yaml` passes them to the container, and the
[deployment guide](deployment.md) lists each one, secrets marked. `backend/.env.example`,
`frontend/apps/web/.env.example` and `frontend/apps/mobile/.env.example` are versioned; `.env` is
ignored. The backend does not start without `JWT_SECRET`.

The clients read their configuration when they are built: the web calls the relative `/api/v1` by
default (`VITE_API_BASE_URL`), so one bundle works on any host; the mobile app gets
`EXPO_PUBLIC_API_BASE_URL`, which ends up inside the app, so it never holds a secret. The one
exception is the quick sign-in of the demo (`VITE_QUICK_SIGN_IN`, `EXPO_PUBLIC_QUICK_SIGN_IN`):
with the flag on, the demo password goes into the build, so that build is local only and never
deployed ([demo script](demo.md)).

- **It would break if:** a credential were committed, or the code asked which environment it runs
  in.
- **Check:** from the repository root, `git ls-files | grep -E '(^|/)\.env$'` prints nothing. The
  `lint` job fails if a `.env` is versioned or if Compose is not valid with `.env.example`.
- **Planned: #200**, `SERVER_FORWARD_HEADERS_STRATEGY` only in the deployment Compose.

## IV. Backing services

**Done.** PostgreSQL is an attached resource: the backend gets it from `SPRING_DATASOURCE_URL` and
its credentials (locally, Spring Boot infers them from Compose). The evidence folder is
`STORAGE_BASE_PATH`. PostgreSQL is the only backing service today: Redis enters only when a
measurement or a second replica asks for it ([ADR-0022](adr/0022-redis-from-measurements.md)), and
the external data sources are not built.

- **It would break if:** moving to another database host meant changing code.
- **Check:** the smoke test of the `build` job ([CI guide](ci.md), *`build`*) starts the image
  against a database container named `smoke-db`, set only through those variables.

## V. Build, release, run

**Partial.**

- **Build:** `backend/Dockerfile` compiles one jar inside the image, and fails if there is more
  than one. The `build` job tags the image with the commit SHA, smoke-tests it and publishes the
  jar copied from that image (`urbanpulse-<sha>.jar`).
- **Release:** the image plus the variables of `.env`, put together by Compose.
- **Run:** `docker compose --profile app up`. The migrations run when the backend starts.
- **Rollback:** going back to an older image does not undo its migrations
  ([deployment guide](deployment.md), *Update and Roll Back*).

- **It would break if:** the server built the code with `git pull`, or the deployed artifact were
  not the one that passed the CI.
- **Check:** the summary of a `build` run on `main` in GitHub Actions names the artifact of that
  commit.
- **Missing:** the image is not pushed to any registry, and there is no server environment to
  release to. The deployment Compose is **Planned: #134, #199**; no issue plans the registry yet.

## VI. Processes

**Partial.** The backend keeps no data in the process or on the container's filesystem: access
tokens are stateless JWT, refresh tokens and every domain record are in PostgreSQL, and two
concurrent edits of the same incident are detected by optimistic locking in the database
([ADR-0020](adr/0020-incident-lifecycle-transitions.md)). Uploaded photos are written to the
evidence folder, a volume ([deployment guide](deployment.md), *Volumes*), never to the container
(#192).

- **It would break if:** user data or files were kept in memory or in the container.
- **Missing:** the sign-up and sign-in rate-limit counters live in the process, so a restart resets
  them ([security guide](security.md)). Accepted limit, until a second replica moves them to Redis
  (ADR-0022).

## VII. Port binding

**Partial.** The backend is a self-contained jar with its own web server, and listens on
`SERVER_PORT` (8080 by default).

- **It would break if:** the port were fixed in the code, or the app needed a server installed on
  the machine.
- **Check:** the smoke test of the `build` job starts the image with `SERVER_PORT=9090` and waits
  for `/actuator/health` on that port.
- **Missing:** the web app is only served by the Vite development server. A web image with nginx,
  as [ADR-0017](adr/0017-reverse-proxy.md) decides, is **Planned: #134, #199**.

## VIII. Concurrency

**Accepted limit.** The system runs one backend replica (ADR-0011). Nothing in the domain stops a
second one, because the state is in PostgreSQL (see VI), but the rate-limit counters would not be
shared. A second replica is one of the triggers of
[ADR-0022](adr/0022-redis-from-measurements.md) for Redis. The load test of QA-1
(`backend/load-tests`, [architecture overview](architecture.md)) measures a single replica.

## IX. Disposability

**Partial.** `java` is PID 1 in the image (exec form of `ENTRYPOINT`), so it receives the
`SIGTERM` of `docker stop`. On it, Spring Boot's graceful shutdown stops taking requests, lets the
ones in progress finish and closes the database pool; its log ends with `Graceful shutdown
complete` and `HikariPool-1 - Shutdown completed`. Start-up takes about 5 s on a developer laptop
(`Started UrbanpulseApplication in ...` in the log).

- **It would break if:** the process were killed instead of stopping, or took minutes to start.
- **Check:** the `build` job fails if the container is still running 30 s after `docker stop`.
  Locally: `docker compose --profile app up -d --build`, then `docker compose stop backend` and
  `docker compose ps -a backend`: it shows `Exited (143)`, stopped by `SIGTERM`; `137` would mean
  it was killed.
- **Not checked:** no test sends a request while the backend stops, and start-up time is not
  measured on the demo machine.

## X. Dev/prod parity

**Partial.** The same backend image runs locally (`--profile app`) and in the CI (#90), with the
same PostGIS image in Compose, in the Testcontainers tests and in the CI. The Java version is the
same in `ci.yml`, `pom.xml` and the two Temurin tags of the Dockerfile ([CI guide](ci.md)).

- **It would break if:** development used another database or other versions.
- **Check:** the `lint` job fails if Compose, `TestcontainersConfiguration.java` and the CI do not
  use the same PostGIS image. The Java version is aligned by hand: no job checks it.
- **Missing:** the web app and the base map tiles only run with Vite. **Planned: #134, #199**.

## XI. Logs

**Done.** The backend writes its log to stdout and keeps no log file. Every line of a request
starts with its request id (`RequestIdFilter`, `X-Request-ID`), the same as the `traceId` of its
error body ([API guide](api.md)), so a user's report leads to its lines.

- **It would break if:** the application wrote and rotated its own log files.
- **Check:** `docker compose --profile app logs backend` shows the application log.

## XII. Admin processes

**Done.** Admin tasks run with the same code and configuration as the application:

- The schema and the reference data are Flyway migrations, run when the backend starts
  ([ADR-0014](adr/0014-database-migrations-flyway.md), [database guide](database.md)): #8, #34.
- The `dev`, `demo` and `perf` datasets are repeatable migrations behind their profiles: #83, #54,
  #84 ([datasets](datasets.md)).
- The first administrator is created by a one-off run of the backend image: #126.
- The base map is built by the one-off `tiles-builder` service of the same Compose file: #38.

- **It would break if:** someone changed the schema or the data by hand on a shared database.
- **Check:** `docker compose --profile app run --rm backend --spring.profiles.include=bootstrap-admin`
  creates the first administrator and exits ([deployment guide](deployment.md), *Create the First
  Administrator*).

## 🔄 Keeping It Current

The pull request that changes how a factor is applied updates its section and its row in the
summary, in the same pull request. The 12F issue form asks for it (*12-factor traceability annex
updated*); issues of other types that touch a factor, such as #200, update it too. A section only
claims what the repository shows when its pull request is merged.
