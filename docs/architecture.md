# 🏛️ Architecture Overview

Why UrbanPulse is built the way it is: the drivers that shape it, a short summary of its parts, and
the quality attribute scenarios that say, with numbers, whether the architecture does its job. The
structure is drawn in the [C4 model](architecture/) and each decision is argued in its
[ADR](adr/); this document links them instead of repeating them.

A quality goal without its measurement conditions cannot be checked (`AGENTS.md`, section 12), so
every scenario below states its source, stimulus, environment, artifact, response and response
measure, how it is measured, and what has been measured so far. The response measures are
**proposed targets**: the team confirms or adjusts them when the first full measurement is taken.

## 🎯 Architecture Drivers

### Functional requirements

The requirements with the most weight on the architecture are the High ones of the
[requirements and prioritization](requirements.md):

| Requirement | Why it shapes the architecture |
| --- | --- |
| RF01, RF02 Identity, roles and permissions | Every operation checks the role and the resource in the API, whatever the client ([ADR-0004](adr/0004-authentication-strategy.md), [security guide](security.md)) |
| RF03, RF04 Report an incident with its position | Geospatial data in the database ([ADR-0003](adr/0003-geospatial-data-management.md)) and a mobile client with location ([ADR-0005](adr/0005-mobile-application-technology.md)) |
| RF06 Consultation and tracking | Two histories, the internal one and the one visible to the citizen, from the same data |
| RF09, RF11, RF12 Validation, assignment and lifecycle | One transition table, checked in one place, with optimistic locking ([ADR-0020](adr/0020-incident-lifecycle-transitions.md)) |
| RF18 Audit | An append-only audit trail written in the same transaction as the change |
| RF26 Controlled degradation | Creating an incident never waits for an external source |

### Quality attributes

In the order the team prioritises them when two of them conflict:

1. **Security and privacy.** Incidents carry positions, photos and personal data of citizens. A
   citizen sees only their own incidents, and staff only what their role allows.
2. **Resilience of reporting.** A citizen can always report, even when a source of urban data is
   down (RF26).
3. **Data integrity and traceability.** A status change and its history are saved together or not
   at all, and relevant actions keep their actor, instant and reason.
4. **Performance of the lists** (inbox, *My reports*, work queue) as the data grows, since they are
   the screens every user opens first.
5. **Maintainability** by a team of five students working in short sprints.
6. **Cost:** the demo runs at zero cost.

### Constraints

- **Course:** a Spring Boot backend with PostgreSQL, starting as a monolith; a REST API designed
  contract first with OpenAPI; containers with Docker; Twelve-Factor; GitHub Flow with reviewed pull
  requests.
- **Budget:** no paid services. External providers must have a free tier, and the base map is
  self-hosted to avoid a tile provider ([ADR-0016](adr/0016-self-hosted-osm-base-map.md)).
- **One machine:** the whole system runs with Docker Compose on one VM, with one backend replica
  ([ADR-0011](adr/0011-deployment-12factor.md)).
- **Time:** the MVP is Sprint 1 (one week, until 2026-11-03) and Sprint 2 (two weeks, until
  2026-11-17). Sprint 3, from 2026-11-18 to 2026-11-24, is for the remaining requirements, and a
  reserve week until 2026-12-01 is for delays and fixes.
- **Data sources:** Málaga open data and OpenStreetMap, with their usage policies
  ([ADR-0002](adr/0002-open-street-map.md), [ADR-0009](adr/0009-urban-data-source.md)).

## 🧱 Architecture Summary

A **modular monolith** ([ADR-0010](adr/0010-modular-monolith.md)): one Spring Boot backend, one
PostgreSQL schema, and modules that are packages organising the code by feature (`identity`,
`incidents`, `urbancontext`, `geospatial`, `notifications`, `analytics`). Two clients share the
same REST API and a set of TypeScript packages ([ADR-0007](adr/0007-client-application-architecture.md)).

| Container | Technology | Decided in | Built |
| --- | --- | --- | --- |
| Web app (staff) | React + Vite | [ADR-0006](adr/0006-frontend-web-technology.md) | Yes |
| Mobile app (citizens) | React Native + Expo | [ADR-0005](adr/0005-mobile-application-technology.md) | Yes |
| Backend API | Spring Boot, REST, contract first | [ADR-0008](adr/0008-backend.md), [ADR-0010](adr/0010-modular-monolith.md) | Yes |
| Database | PostgreSQL with PostGIS, Flyway migrations | [ADR-0001](adr/0001-database.md), [ADR-0003](adr/0003-geospatial-data-management.md), [ADR-0014](adr/0014-database-migrations-flyway.md) | Yes |
| Tiles builder | One-off process that builds the base map | [ADR-0016](adr/0016-self-hosted-osm-base-map.md) | Yes |
| Evidence storage | Docker volume behind a storage interface | [ADR-0013](adr/0013-evidence-storage.md) | Yes, in the API (#192), the mobile upload (#193) and the web gallery (#194) |
| Reverse proxy | nginx, the single published entry point, that also serves the web app and the base map | [ADR-0017](adr/0017-reverse-proxy.md) | Yes (#199, #200) |
| Cache | Redis | [ADR-0022](adr/0022-redis-from-measurements.md), which supersedes [ADR-0012](adr/0012-cache-redis.md) | No, until a measurement or a second replica asks for it |

Where to look for more:

- **Structure:** the [C4 model](architecture/) (`workspace.dsl`), with the system context, the
  containers, the components of the backend, the web app, the mobile app and the database, the
  deployment in development and on a single host, and two dynamic views: sign-in with refresh
  rotation and reporting an incident. See *C4 Model* below.
- **Decisions:** the [ADRs](adr/). Check each status: 0018 and 0019 are only *Proposed*.
- **API:** the [OpenAPI contract](api/openapi.yml) and the [API guide](api.md).
- **Deployment:** the [deployment guide](deployment.md).
- **Data:** the [database guide](database.md) and the [datasets](datasets.md).

## 🗺️ C4 Model

[`architecture/workspace.dsl`](architecture/workspace.dsl) (Structurizr DSL) is the only source of
the model. It shows the decided architecture, so it includes containers that accepted ADRs decide
but are not built yet (see *Built* above); the deployment views show what runs where.

[`architecture/UrbanPulse-C4.drawio`](architecture/UrbanPulse-C4.drawio) is generated from it, one
page per view, for readers without Structurizr. Never edit it by hand: change the DSL and, from the
repository root, run

```bash
docker compose -f docs/architecture/compose.yaml run --rm drawio
```

The generator (`architecture/drawio/`) exports the workspace with Structurizr, lays out each view
with Graphviz and writes the file; it needs only Docker. The `architecture` CI job regenerates it
and fails if the committed file differs ([CI guide](ci.md#architecture--c4-model)).

To browse the model in Structurizr, run this from the repository root and open
`http://localhost:8080` (*Diagrams* lists every view). It saves `workspace.json` and `.structurizr/`
next to the DSL; Git ignores both.

```bash
docker run -it --rm -p 8080:8080 -v "$PWD/docs/architecture:/usr/local/structurizr" structurizr/structurizr:2026.09.19 local
```

## 📐 Quality Attribute Scenarios

| ID | Quality attribute | Scenario | Status |
| --- | --- | --- | --- |
| QA-1 | Performance | The incident list and detail with the large dataset | Met on a developer laptop; not yet on the demo machine |
| QA-2 | Resilience | An incident is created while an external source is down | Holds by construction; no test yet |
| QA-3 | Security | Sign-in attempts over the limit are rejected | Tested on every pull request |
| QA-4 | Availability, durability | A backend restart loses no confirmed data or evidence | Shutdown tested in CI; data check manual |

### QA-1: Response time of the incident list and detail

| Part | Value |
| --- | --- |
| Source | Staff on the web app and citizens on the mobile app |
| Stimulus | Open the first page (20 incidents) of their list, or the detail and history of one incident |
| Environment | Normal operation; `perf` dataset with 100,000 incidents ([datasets](datasets.md)); 20 concurrent users for 5 minutes; one backend replica and the database on the same machine |
| Artifact | Backend API (`GET /incidents`, `GET /incidents/{id}`, `GET /incidents/{id}/status-changes`) and the database |
| Response | The page or the detail is returned, filtered by role |
| Response measure | p95 under 500 ms and no errors, for every operation and role. Each database query takes under 10 ms (median, warm cache) |

**How it is measured.** In two parts:

- **Database:** `EXPLAIN (ANALYZE, BUFFERS)` of the SQL each operation runs, on the `perf` dataset,
  as *Indexes* in the [database guide](database.md) describes.
- **HTTP:** a load test against the running backend, recording p50, p95 and p99 in *Measurements*
  in the [datasets guide](datasets.md), with the conditions that template asks for.

**First measurement (database, 2026-11-02, #132).** With the indexes of
`V10__incidents_index_main_queries.sql`, every main query is under 10 ms:

| Query | Median |
| --- | --- |
| *My reports*, page of 20 (the citizen with the most incidents, 5,901) | 0.5 ms |
| Operator inbox, every status, page of 20 | 0.5 ms |
| Operator inbox, one status, page of 20 | 0.4 ms |
| Technician list, page of 20 (2,089 active assignments) | 8.1 ms |
| History of one incident | 0.1 ms |

Conditions and the rest of the queries (the counts, the technician detail) are in the
[database guide](database.md#main-queries).

**First measurement (HTTP, 2026-11-03, #155).** With the k6 script
`backend/load-tests/qa1-incidents.js`, every operation of every role has a p95 under 30 ms and no
request failed:

| Operation | p95, slowest role |
| --- | --- |
| List, page of 20 | 27.5 ms (technician) |
| Detail | 4.9 ms (citizen, administrator) |
| History | 6.2 ms (citizen) |

How to run it, the conditions and every role are in *Load test of QA-1* in the
[datasets guide](datasets.md#load-test-of-qa-1). It was measured on a developer laptop with the
backend, the database and k6 on the same machine. QA-1 asks for the machine the demo runs on, so it
must be measured again there once that machine exists.

### QA-2: Create an incident while an external source is down (RF26)

| Part | Value |
| --- | --- |
| Source | A citizen on the mobile app |
| Stimulus | Report an incident (`POST /incidents`) |
| Environment | An external source (Málaga open data, Nominatim, a weather or traffic provider) does not answer or answers with errors |
| Artifact | Backend API, `incidents` module |
| Response | The incident is saved with the data the citizen sent, and the context that could not be obtained is marked as pending, never as zero or empty |
| Response measure | 100 % of the valid reports are created (`201`), and the response time stays within QA-1's 500 ms at p95: the request never waits for a source timeout |

**How it is measured.** An integration test in which the adapter of each source fails or times out
(a test double, never the real provider), checking the `201`, the saved incident and the pending
context. It is case 1 of section 9 of `AGENTS.md`.

**Status.** It holds by construction: creating an incident (`IncidentReportService`) uses only the
database and calls no external source, because none is integrated yet. The test is written with
the first source adapter (RF21–RF25, after the MVP), which must enrich the incident after it is
saved, not before.

### QA-3: Sign-in attempts over the limit are rejected (ADR-0004)

| Part | Value |
| --- | --- |
| Source | An attacker trying passwords, possibly with parallel requests |
| Stimulus | A sixth failed sign-in for the same email from the same client address within 15 minutes, or an eleventh sign-up from one address within an hour |
| Environment | Normal operation, one backend replica |
| Artifact | Backend API, `identity` module (`POST /sessions`, `POST /registrations`) |
| Response | `429 TOO_MANY_ATTEMPTS` with `Retry-After`, even when the password is right |
| Response measure | 0 attempts accepted past the limit, including a burst of parallel requests |

**How it is measured.** Automatically, in the `test` CI job on every pull request:
`AuthenticationApiIT` (`repeatedWrongPasswordsAreRateLimited`,
`repeatedSignUpsFromOneClientAreRateLimited`) and `AttemptLimiterTest`
(`concurrentAttemptsNeverGoPastTheLimit`). The limits are in the [security guide](security.md).

**Status.** Met. Known limits: the counters live in memory, so a restart resets them and they are
only correct with one replica; [ADR-0022](adr/0022-redis-from-measurements.md) says when they move
to Redis.

### QA-4: A backend restart loses no confirmed data or evidence (ADR-0011, ADR-0013)

| Part | Value |
| --- | --- |
| Source | The operator of the deployment, or a crash |
| Stimulus | The backend container is stopped (`SIGTERM`) and started again, or replaced by a new image |
| Environment | Normal operation, with incidents, history and evidence already confirmed to the clients |
| Artifact | Backend container, the `postgres-data` and `evidence_data` volumes |
| Response | The backend stops cleanly and, once started, serves every confirmed incident, history entry and evidence file |
| Response measure | 0 confirmed records or files lost; the container stops within 30 s of `SIGTERM`; the API is healthy again within 60 s of the start |

**How it is measured.**

- **Clean stop:** the `build` CI job stops the image with `docker stop` and fails if it is not
  stopped within 30 s ([CI guide](ci.md#build--one-artifact-per-commit-and-smoke-test)).
- **No data lost:** a manual check with the Compose stack ([deployment guide](deployment.md)):
  create an incident, run `docker compose --profile app restart backend`, wait for
  `/actuator/health`, and read the incident and its history again, and download its photo
  (`getAttachmentContent`).

**Status.** The clean stop is met on every pull request. The backend keeps no state in its
container (the data and the photos are in the volumes), but the no-loss check has not been
recorded yet (T1 of #67).

## ⚖️ Accepted Risks

Known limits the team accepts for now. A review does not report them (*Sprint review* in
`CONTRIBUTING.md`); each one is revisited when its source says.

| Risk | Effect | Source |
| --- | --- | --- |
| The sign-in and sign-up rate-limit counters live in the backend process | A restart resets them, and they are only correct with one replica | [Security guide](security.md), *Rate limits*; [ADR-0022](adr/0022-redis-from-measurements.md) |
| The sign-in limit is per email and address | One address can still try 5 passwords on each of many emails | [Security guide](security.md), *Rate limits* |
| Clients behind one public address share the rate limits | A university wifi shares the 10 sign-ups per hour, and someone on the same network can block a person's sign-in | [Security guide](security.md), *Rate limits*; #159 |
| The API documentation is public (`/swagger-ui.html`, `/v3/api-docs`, `/openapi.yml`) | Anyone can read the contract, which is public by design | [Security guide](security.md), *Permission matrix*; [API guide](api.md) says how to turn it off |
| The audit trigger guards against mistakes, not against the database owner | Someone with the owner or superuser account can disable it; a user who appears in the audit trail cannot be deleted | [Security guide](security.md), *Audit trail* |
| Everything runs on one machine | If the machine stops, the whole system stops; nginx is also the single entry point, but keeps no data | [ADR-0011](adr/0011-deployment-12factor.md); [deployment guide](deployment.md), *If nginx Goes Down* |
| A refresh that sign-out cannot use to revoke the session (it answers after the sign-out, or the `DELETE` itself fails) creates a session the device no longer holds | That session stays alive on the server until its refresh token expires (`REFRESH_TOKEN_TTL`, 14 days by default); nobody can use it without the token, which the device discarded | [Security guide](security.md), *Sessions and passwords*; #237 |

## 📋 ADRs Without a Measurable Evaluation

ADR-0013, ADR-0014, ADR-0017, ADR-0020, ADR-0021 and ADR-0022 state a metric and a threshold,
and ADR-0016 has thresholds for size and build time ("smooth on a mid-range phone" is not
measurable yet). ADR-0018 and ADR-0019 are only *Proposed*, and ADR-0012 is superseded by
ADR-0022. The accepted ADRs below have no measurable evaluation; the scenario that would evaluate
each one:

| ADR | Scenario that would evaluate it |
| --- | --- |
| [0001](adr/0001-database.md), [0003](adr/0003-geospatial-data-management.md) PostgreSQL with PostGIS | QA-1, and when the map exists (#53), a viewport query with the `perf` dataset under the same p95 |
| [0002](adr/0002-open-street-map.md), [0009](adr/0009-urban-data-source.md) OpenStreetMap and Málaga open data | QA-2, plus 0 requests over each provider's usage policy (Nominatim allows one per second) |
| [0004](adr/0004-authentication-strategy.md) Authentication | QA-3, plus a revoked or rotated refresh token is never accepted (tested by #125) |
| [0005](adr/0005-mobile-application-technology.md), [0006](adr/0006-frontend-web-technology.md), [0007](adr/0007-client-application-architecture.md) Clients | A change in the contract breaks `pnpm typecheck` in both apps before the merge, through the generated `api-client` (`frontend` CI job) |
| [0008](adr/0008-backend.md) Spring Boot, contract first | 100 % of the HTTP test responses validated against the contract (`ApiContract.EveryResponse`, [testing guide](testing.md)) |
| [0010](adr/0010-modular-monolith.md) Modular monolith | A feature of one module changes the files of that module only; review the pull requests of Sprint 2 and count the exceptions |
| [0011](adr/0011-deployment-12factor.md) Compose and Twelve-Factor | QA-4, plus the image that passed CI is the one deployed (same digest), configured only by environment variables |
| [0015](adr/0015-ci-pipeline-and-pre-commit-formatting.md) CI | The pipeline of a pull request finishes in under 15 minutes at p95, and no formatting failure reaches the CI when the hook is installed |

Adding the evaluation to the ADR itself, or writing it in a new one, is left to the owner of each
decision.
