# UrbanPulse — AI Implementation Guide

> **Delivery window:** one week, starting 2026-10-20.
> **Audience:** an agentic coding assistant with repository and terminal access.
> **Companion document:** `docs/guias/guia-persona.md` (human operator manual).
> **Status:** this is a plan. Nothing is implemented merely because it appears here.
> **Revision:** 2026-10-20 r2 — reconciled with the repository at `778fbc4` (Spring Modulith removed in `6a2c356`, PR-title check, CI smoke test, issue-template actor split).

This guide is long (about 1,800 lines; most tools cannot read it in one pass). Before changing code, read **sections 0–5 completely and the task card you are executing**; read sections 6–9 when the card or a question needs them. If a task prompt conflicts with this guide, stop and ask the human. Do not invent requirements, files, commands, test results, or completed features.

If this guide and the repository disagree, the repository is the evidence of the current state (section 1.1). Report the discrepancy in your plan or completion report instead of following stale text.

---

## 0. Operating protocol

### 0.1 One task per session

- Execute **exactly one task card** per branch and pull request.
- Do not start the next task until the human has reviewed the current one and marked it accepted.
- Do not combine a feature with unrelated refactors, formatting changes, dependency upgrades, or documentation rewrites.
- If a task requires a change outside its declared scope, stop and report the dependency instead of making the change.

### 0.2 Required reading order

1. `AGENTS.md`.
2. This guide, sections 0–5 and the task card you are about to execute.
3. The accepted ADRs relevant to the task, and the decision records approved for this delivery (section 5).
4. `git log --oneline -15`: recent commits can carry team decisions that the documents do not reflect yet (for example `6a2c356` removed Spring Modulith while ADR-0010 still describes it).
5. The team's class diagram, when it is in the repository (decision D12), for entity names and relationships.
6. The current code, tests, OpenAPI contract, migrations, and `CHANGELOG.md`.
7. The human's task prompt and the decisions they approved.

### 0.3 Before writing code

Report, in a short message:

- The task ID and its goal.
- What is in scope and out of scope.
- The files you expect to touch.
- The decisions or assumptions that could change the public contract, permissions, domain rules, or data model.
- Any blocking question. If the answer changes a decision listed in section 5.1, wait for the human.

Do not write code while a blocking decision is unresolved.

### 0.4 During implementation

- Keep the change as small as the task allows.
- Follow existing conventions; where none exist, use English identifiers and the structure in this guide.
- Add or update tests in the same change. A feature without tests is not done.
- Add or update the OpenAPI contract, migration, `.env.example`, documentation, and `CHANGELOG.md` when the task changes them.
- In `CHANGELOG.md`, add entries under `## Not published` using the headings the file already has (`Added`, `Modified`, `Fixed`). Do not rename them to `Changed`/`Removed` on your own; that mismatch with `AGENTS.md` is a team decision.
- If you add a mandatory configuration property, also pass it in the smoke-test step of the `build` job in `.github/workflows/ci.yml` (see `docs/ci.md`, section on `build`). The packaged jar starts with the default profile and only the variables that step exports.
- Run the applicable checks yourself. Never claim a check passed if you did not run it.
- Do not disable, skip, weaken, or delete a check to make the build pass.
- Do not commit, push, merge, or open a pull request unless the human explicitly asks.
- Do not edit `docs/guias/*` unless the task explicitly says so.

### 0.5 Stop conditions

Stop and report instead of guessing when:

- A missing decision changes the domain, permissions, public contract, cost, or architecture.
- The baseline build is already red and you cannot distinguish your failure from the existing one.
- The task requires real credentials, paid services, or a production external provider.
- The task requires touching another task's files or another module's internals.
- The source documents conflict and the hierarchy in section 1.1 does not resolve it.
- You cannot run the required verification and the limitation changes the meaning of "done".
- You exceed the task timebox without a demoable, tested result.

### 0.6 Completion report

Use this template at the end of every task:

```text
TASK: <ID and name>
STATUS: done | blocked | partial

SCOPE:
- Implemented:
- Explicitly not implemented:

CHANGED FILES:
- <path>: <reason>

CONTRACT / DATA:
- OpenAPI changes:
- Migrations:
- Environment variables:

VERIFICATION:
- <command>: <actual result>
- Manual check: <what was exercised and observed>
- Not run: <what and why>

RISKS / LIMITATIONS:
- <only real, known items>

NEXT:
- <one sentence; do not start the next task>
```

For a blocked task, include the exact question or failing command and what you tried.

---

## 1. Source of truth and current state

### 1.1 Conflict hierarchy

When documents disagree, use this order:

1. The assignment requirements and the invariants in `AGENTS.md`.
2. Accepted ADRs and decisions explicitly approved by the human.
3. The current code and tests as evidence of the actual state, not as proof of intent.
4. Proposed ADRs, README, issue templates, and CI documentation.

If an accepted ADR and the code disagree, find out which one is newer and why:

- The code is behind the ADR (the ADR describes something not built yet): the code must change. Example: `SecurityConfig` uses HTTP Basic while ADR-0004 accepts JWT.
- The code is ahead of the ADR (a commit records a newer team decision): the ADR is stale and must be amended by the team. Example: commit `6a2c356` removed Spring Modulith and `ModularityTest` "because enforcing module boundaries would force us to drop relationships that the class diagram already defines", but ADR-0010 still describes Modulith. Follow the newer decision, do not reintroduce what was removed, and flag the stale ADR (decision D0).

If two accepted documents disagree, or you cannot tell which is newer, stop and ask.

Do not silently "fix" a document by changing its meaning. Record the discrepancy and let the human decide.

### 1.2 Verified starting point

| Area | Actual state |
|---|---|
| Backend | `urbanpulse/`, Spring Boot 4.1.1, Java 25, Maven Wrapper |
| Architecture | Spring Modulith and `ModularityTest` were **removed** in `6a2c356` (2026-10-20). Only package `security` exists; no domain modules; no build check of module boundaries |
| Database | PostgreSQL 18 + PostGIS 3.6 via `urbanpulse/docker-compose.yaml`; JPA `ddl-auto: validate`; no `hibernate-spatial` |
| Migrations | None. No migration tool is decided (no ADR) and there is no Flyway dependency yet (decision D10) |
| Security | `SecurityConfig` permits `/actuator/health/**` and uses HTTP Basic for everything else |
| Roles | ADR-0004 names them `user`, `admin`, `technician`, `operator`, `analyst` (`user` = citizen) |
| JWT | `jjwt` dependencies exist; no JWT implementation |
| API contract | No `docs/api/openapi.yaml`; no generated client |
| Evidence | No storage abstraction or attachment model |
| Cache | No Redis or other cache; ADR-0012 is **Proposed** |
| Stateful services | PostgreSQL/PostGIS only; no Redis, no nginx, no backend image |
| Web | No `apps/web` |
| Mobile | No `apps/mobile` |
| Shared packages | No `packages/*` |
| CI | `.github/workflows/ci.yml` with `lint`, `test`, `build` (backend only; `build` smoke-tests the jar with env vars only); `.github/workflows/pr-title.yml` rejects PR titles that are not Conventional Commits |
| Quality gates | Spotless AOSP (pre-commit hook in `.githooks/`), SpotBugs, JaCoCo line coverage `0.80` |
| Branch state | Current branch `boilerplate`; `main` is behind it. Both workflows trigger on `boilerplate` with a `TEMPORARY: remove boilerplate before merging into main` comment |
| Client split | `.github/ISSUE_TEMPLATE/01-feature.yml` says web = operators, analysts, administrators; mobile = citizens, technicians (decision D11) |
| Local secrets | `urbanpulse/.env` exists locally and is ignored; only `.env.example` is versioned |

### 1.3 Document status

| ADR | Status | Implication |
|---|---|---|
| 0001 PostgreSQL | Accepted | Use PostgreSQL; schema changes through migrations |
| 0002 OpenStreetMap | Accepted | OSM for maps/geocoding; respect usage policy and cache |
| 0003 PostGIS | Accepted | Store locations as `geography(Point, 4326)`; spatial predicates in DB |
| 0004 Authentication | Accepted | JWT access + rotating refresh tokens; server-side authorization. Says the token model works "without relying on browser cookie behaviour", so a web refresh cookie (D4) is an amendment |
| 0005 Mobile | Accepted | React Native with Expo and TypeScript |
| 0006 Web | Accepted | React + TypeScript + Vite |
| 0007 Client architecture | Accepted | Monorepo with `apps/*` and shared `packages/*`; names React Navigation for mobile (Expo Router is built on it; say so if you use it) |
| 0008 Backend | Accepted | Spring Boot REST API, contract-first OpenAPI |
| 0009 Urban data | Accepted | Málaga OpenData primary, OSM fallback, never block a report |
| 0010 Modular monolith | Accepted, **partly stale** | Still valid: one deployable, package-by-feature modules, atomic work in one transaction, events only for after-commit side effects. Stale since `6a2c356`: `@ApplicationModule`, `ModularityTest`, `DocumentationTest`, `@ApplicationModuleListener`, and (by the commit's reasoning) the ban on cross-module JPA relationships. Pending amendment (D0) |
| 0011 Deployment | Accepted | Docker Compose, 12-factor configuration |
| 0012 Redis cache | **Proposed** | Do not add Redis without an accepted decision |
| 0013 Evidence storage | **Proposed** | Local volume behind `StorageService`; acceptance requires human approval |
| 0014 nginx proxy | **Proposed** | nginx as single entry point; acceptance requires human approval |
| 0015 CI | Accepted | Check-only CI, pre-commit formatting, squash merging |

### 1.4 Known documentation debt

Do not pretend these files exist:

- `docs/deployment.md` is linked from `README.md` but is missing.
- `docs/architecture/workspace.dsl` is referenced by ADR-0010/0011 but is missing.
- `docs/infra/docker/DOCKER.md` is referenced by ADR-0013/0014 but is missing.
- The issue template mentions `docs/api/openapi.yml`; this guide standardizes on `docs/api/openapi.yaml`.
- The issue template and `docs/ci.md` mention job names such as `test-unit`, `test-integration`, `quality`, and `smoke`; the actual workflow jobs are `lint`, `test`, and `build`.
- ADR-0011 refers to `compose.yaml`, `compose.dev.yaml`, and `backend/Dockerfile`; the repository currently has `urbanpulse/docker-compose.yaml` and no backend image.
- `README.md` describes a backend skeleton; that is accurate today but will become stale as the delivery progresses. It also still lists Spring Modulith in the stack.
- Spring Modulith/`ModularityTest` is still described in ADR-0010, ADR-0015, `docs/ci.md` (section 7.1 and the test list) and `docs/ci-setup.md`, although `6a2c356` removed both.
- `CHANGELOG.md` uses `Added`/`Modified`/`Fixed`; `AGENTS.md` asks for `Added`/`Fixed`/`Changed`/`Removed`.
- The issue templates mention `docs/api.md`, which does not exist.
- The team's class diagram, cited by `6a2c356` as the reason to drop Modulith, is not in the repository (decision D12).

The Modulith references and missing decision records are fixed in P00 (documentation-only PR), because every later session reads those documents.

Task D02 reconciles the documents that describe implemented behavior. Until then, do not claim these documents are current.

---

## 2. Delivery scope

### 2.1 Priority definitions

- **P0:** required for the demo and the minimum deliverable. Do not cut without an explicit human decision.
- **P1:** valuable and expected if P0 is green by the checkpoint. Cut first when time is short.
- **P2:** backlog or extension. Explicitly outside this one-week delivery.

The full RF01–RF26 scope cannot be completed well in one week. A smaller, working vertical slice is worth more than a broad, broken implementation.

### 2.2 RF coverage matrix

| RF | Scope | Priority | Tasks | Verification |
|---|---|---|---|---|
| RF01 Identity and access | Register, login, refresh, logout, session | P0 | B01, W01, M01 | Auth unit/integration tests; manual web/mobile login |
| RF02 Roles and permissions | Citizen, operator, technician, admin, analyst | P0 | B01, W01, M01 | Permission tests for every endpoint |
| RF03 Register incident | Title, description, category optional, location | P0 | B02, W02, M02 | Create with and without category; validation tests |
| RF04 Location | Coordinates, accuracy, normalized address | P0 | B02, B07, W02, M02 | Coordinate range/order tests; PostGIS assertion |
| RF05 Evidence | Upload, metadata, validation, authorized download | P0 | B03, W02, M02 | Valid/invalid/oversized/unauthorized tests |
| RF06 Consultation and tracking | Lists, detail, citizen timeline | P0 | B02, B06, W02, M02 | Ownership and timeline tests |
| RF07 Search and filters | Status, category, priority, date, text, pagination | P0 | B06, W03 | Filter/pagination/stable-order tests |
| RF08 Map | Bbox endpoint, web map, mobile location | P0 | B06, W03, M02 | Bbox inclusion/exclusion; manual map check |
| RF09 Validation | Validate, reject, request information | P0 | B04, B05, W04 | Transition and authorization tests |
| RF10 Priority | Manual priority with justification and origin | P0 | B04, W04 | Missing justification rejected; audit row |
| RF11 Assignment | Department/technician assignment and history | P0 | B05, W04 | Assignment permissions/history tests |
| RF12 Lifecycle | Centralized transition matrix | P0 | B04, W04 | Invalid transition leaves no partial state |
| RF13 Collaboration | Public and internal comments | P0 | B05, W04 | Internal notes never exposed to citizen |
| RF14 Notifications | In-app notifications | P1 | B10, W05, M03 | Event-to-notification test |
| RF15 Statistics | Basic role-scoped indicators | P2 | B11, W05 | Aggregation and privacy tests |
| RF16 Duplicates | Suggestions that never block creation | P1 | B09 | Candidate list; creation still succeeds |
| RF17 Zonal analysis | District/area aggregation | P2 | B11 | Aggregation test |
| RF18 Audit | Actor, time, reason, before/after | P0 | B04, W04 | Audit rows for decisions |
| RF19 Reports | CSV export | P2 | B11, W05 | Export respects filters/permissions |
| RF20 AI assistance | Classification/priority/summary with confidence | P2 | B12 | Only with a real provider; no fake confidence |
| RF21 Neighborhood/district | Point-in-polygon or documented fallback | P1 | B08 | Source/quality/geometry test |
| RF22 Urban asset | Nearest asset, alternatives, confidence | P1 | B08 | Candidate alternatives preserved |
| RF23 External context | Normalized observations with provenance | P0 | B07 | Source/time/quality persisted |
| RF24 Contextual view | Context panel with status and source | P0/P1 | B07, W04 | Available/stale/unavailable states |
| RF25 Historical correlation | Correlation with historical variables | P2 | B12 | Out of delivery |
| RF26 Controlled degradation | Report succeeds when source is down | P0 | B07 | Provider-down integration test |

### 2.3 P0 demo definition

At the end of P0, a human can:

1. Register and log in as a citizen.
2. Create an incident with a location, optional category, and evidence.
3. See the incident in the citizen list and tracking timeline.
4. Log in as an operator, filter the queue, open the incident, validate it, set a justified priority, and assign it.
5. Log in as the assigned technician, start and resolve it with a note.
6. See the resolved status as the citizen, without seeing internal notes.
7. See the incident on the map.
8. Stop or break the external provider and create another incident: creation still succeeds and the context is `PENDING` or `UNAVAILABLE`, never falsely `AVAILABLE`.
9. Show audit/status history with actor, time, and reason.
10. Do the mobile flow: login, report with GPS/camera, list, detail/tracking.

The demo must also show `./mvnw verify` and the web/mobile checks passing.

Which client each actor uses depends on D11. With the recommended default, steps 1–3 and 6 happen on **mobile** (citizen), steps 4, 7 and 9 on **web** (operator), and step 5 on **web** as a documented P0 exception (technician on mobile is P1). In that case W02 (web citizen flow) becomes P1.

### 2.4 Cut line

If P0 is not green at a checkpoint, cut in this order:

1. P2 tasks and all AI/RAG work.
2. P1 tasks: notifications, duplicates, district/assets, admin/analyst, embedded mobile map, Docker/nginx.
3. Reduce mobile to auth + create + list + detail (keep evidence and location).
4. Reduce web operator to queue + detail + validate/reject/assign/start/resolve (keep audit and permissions).
5. Never cut: backend authorization, transactionality, audit for decisions, evidence validation, degraded external context, citizen/mobile core flow, tests for those behaviors, README/CHANGELOG, demo script.

If the delivery is still at risk, the human must explicitly approve any further cut. Do not silently remove a P0 behavior.

---

## 3. Non-negotiable rules

### 3.1 Domain and data

- A report accepts title, description, position, and an **optional** category. Never make category mandatory.
- Preserve latitude, longitude, accuracy, and normalized address when available. Do not depend on an external source to accept a report.
- A source outage never prevents incident creation. Model `PENDING`, `AVAILABLE`, `PARTIAL`, `UNAVAILABLE`, and `STALE`; never present absent data as zero or current.
- Unknown data is not zero. Preserve provenance: source, observed time, ingested time, unit, quality, and validity.
- Duplicate suggestions never block a new report. Rejection for duplication requires authorization, reason, and history.
- Manual priority requires justification and origin. Assisted decisions are P2 and must record model/version/confidence.
- Assignment respects permissions and rules. Keep assignment history; do not overwrite it.
- Status changes, priority decisions, assignments, and relevant admin actions are auditable. Append history; never delete it.
- Keep internal notes, personal data, and restricted evidence out of citizen responses.
- Status changes and their history are atomic: one transaction, no partial state.
- Use optimistic locking for concurrent incident edits. Test stale-version behavior.

### 3.2 Architecture

- One deployable Spring Boot modular monolith. One database, one schema.
- **No Spring Modulith.** The team removed it in `6a2c356`. Do not add `spring-modulith-*` dependencies, `package-info.java` with `@ApplicationModule`, `ModularityTest`, or `@ApplicationModuleListener`. If you think boundary verification is needed, propose it; do not add it.
- Each module is a package `es.uma.urbanpulse.<module>` (package by feature) with its own internal layers (`controller`, `service`, `repository`, `entity`, `dto`, `mapper`), as ADR-0010 describes. Create a module package only in the task that first puts code in it (`AGENTS.md`: no empty folders).
- Boundaries are now a convention checked in review, not by the build: call another module through its service in the module root; do not reach into another module's `repository` or `controller`.
- Cross-module JPA relationships: **pending D0**. Until D0 is recorded, a relationship is allowed only if the team's class diagram (D12) defines it; where the diagram is silent, store the other entity's UUID. Never use a relationship to bypass authorization or to serialize another module's entity.
- No network calls between modules. Direct calls, dependency inversion, or in-process events only.
- Events are for side effects after commit (enrichment, notifications, statistics). Without Modulith use plain Spring: `ApplicationEventPublisher` plus `@TransactionalEventListener(phase = AFTER_COMMIT)` and `@Async` on a bounded executor. There is no event publication registry: an event can be lost if the process stops after the commit, so every consumer needs a recovery path (for enrichment: context stays `PENDING` and an operator can refresh it). Do not use events for anything that must be atomic with the incident write.
- Do not introduce Redis, Kafka, a broker, Kubernetes, Terraform, microservices, or a second database without an accepted ADR and an observable problem.
- Do not move or rewrite the backend to satisfy an indicative folder layout. Keep `urbanpulse/` and add `apps/` and `packages/` around it.

### 3.3 API and security

- Base path: `/api/v1`.
- OpenAPI is the public contract: `docs/api/openapi.yaml`. Update it in the same change as the behavior.
- Validate all input on the server. Client validation is only a convenience.
- Check permissions on the specific action and resource. Authentication or knowing an ID is not authorization.
- One rule for hidden resources: a resource outside the caller's scope returns `404` (do not confirm it exists); a resource the caller can see but an action they cannot perform returns `403`. Apply it to incidents, evidence, comments, history and context alike.
- Never expose entities, password hashes, refresh tokens, internal notes, storage keys, or unrelated relationships.
- Use RFC 9457 `ProblemDetail` responses with a stable `code`, safe `detail`, and field errors. Do not leak stack traces.
- Paginate every list with a default and a maximum. Use stable ordering with a tiebreaker.
- Use UUIDs for identifiers and ISO-8601 UTC instants. Do not lose the difference between observed and ingested time.
- Do not log passwords, tokens, authorization headers, personal data, or evidence contents.
- No server secrets in web or mobile bundles. No `.env` in version control.
- Access tokens are short-lived and sent in `Authorization: Bearer`. Refresh tokens are opaque, rotated, hashed at rest, and revocable.
- Web refresh token: `HttpOnly`, `SameSite=Strict`, `Secure` when HTTPS. Mobile refresh token: SecureStore. Access token: memory, not localStorage.
- No wildcard CORS. Use the Vite proxy in development and same-origin nginx in production.
- Swagger UI and API docs are for development; they are not a production authorization bypass.

### 3.4 Evidence

- Store binaries outside PostgreSQL, behind `StorageService`.
- Generate object keys server-side (UUID). Never use a user-supplied filename or path.
- Validate size, declared type, and magic bytes. Do not trust the extension or `Content-Type` alone.
- P0 policy: JPEG, PNG, WebP, PDF; maximum 10 MB. Video is P2.
- A storage key is not a public URL. Download requires authorization on every request.
- Write the file atomically; if metadata persistence fails, delete the file. Do not leave partial records.
- Use safe response headers (`X-Content-Type-Options: nosniff`, controlled `Content-Disposition`).

### 3.5 External data

- Encapsulate each provider behind an adapter and normalize before use.
- Configure timeouts and bounded retries. Never retry a rate-limit response blindly.
- Respect Nominatim/Overpass usage policies. Send an identifiable User-Agent, cache, and never call them once per user action from the client.
- Persist provenance-bearing observations in PostgreSQL. Do not use a cache as the only copy of durable data.
- P0 cache: a small in-process TTL cache behind an interface. Redis is P2 and ADR-0012 is Proposed.
- Tests must not call real providers. Use fakes or fixtures. Real integration tests require explicit human approval.
- Do not invent district boundaries, asset inventories, or confidence values. If the source is missing, mark it unavailable and document the limitation.

### 3.6 Tests and quality

- Unit tests for invariants, transitions, priority, permissions, and validation.
- Integration tests with Testcontainers/PostGIS for persistence, migrations, transactions, spatial queries, and critical adapters.
- HTTP tests for validation, errors, authorization, pagination, and serialization.
- Web tests for critical components/flows; mobile tests for forms/session; manual device checklist.
- No real time, execution order, external service, or network in deterministic tests. Use a controllable `Clock` where time matters.
- Keep JaCoCo line coverage at or above `0.80`. Do not lower the threshold. Add meaningful tests, not trivial getters.
- Run Spotless before committing. Do not reformat unrelated files.
- A test that only reproduces the implementation is not enough; assert observable behavior.

### 3.7 Git, documentation, and honesty

- Branch per task: `feature/<task-id>-<slug>` or `fix/<slug>`.
- Conventional Commits when asked to commit: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `ci:`, `chore:`.
- Update `CHANGELOG.md` in the same PR as the behavior.
- Do not commit generated build output, `target/`, `node_modules/`, `.expo/`, evidence files, `.env`, or secrets.
- Do not claim a feature works because code exists. Demonstrate it.
- Do not claim tests passed if they were skipped or not run.
- Do not mark a P1/P2 RF as done in the demo if it was not implemented.

---

## 4. Target architecture and conventions

### 4.1 Repository layout

```text
urban-incidence-manager/
|-- urbanpulse/                 existing backend; do not move
|   |-- pom.xml
|   |-- docker-compose.yaml     local database only
|   |-- src/main/java/es/uma/urbanpulse/
|   |-- src/main/resources/db/migration/
|   `-- src/test/java/es/uma/urbanpulse/
|-- apps/
|   |-- web/                    React + Vite + TypeScript
|   `-- mobile/                 Expo + React Native + TypeScript
|-- packages/
|   |-- domain/                 enums, labels, zod schemas, formatters
|   |-- api-client/             generated OpenAPI types + typed fetch wrapper
|   `-- ui/                     design tokens and tiny platform-agnostic pieces
|-- docs/
|   |-- api/openapi.yaml
|   |-- adr/
|   `-- guias/
|-- infra/
|   `-- nginx/                  only if D01 is executed
|-- compose.yaml                only if D01 is executed
|-- package.json
|-- pnpm-workspace.yaml
|-- tsconfig.base.json
`-- .npmrc
```

The backend stays in `urbanpulse/`. Adding `apps/` and `packages/` is consistent with ADR-0007; moving the backend is not worth the risk in this delivery.

### 4.2 Backend modules

| Module | Responsibility | Public API examples |
|---|---|---|
| `identity` | Users, roles, credentials, refresh tokens, auth use cases | `UserSummary`, `UserService`, `UserPrincipal`, `Role` |
| `incidents` | Incidents, categories, departments, assignments, comments, attachments, lifecycle | `IncidentService`, `IncidentSummary`, `IncidentContextService`, events |
| `audit` | Append-only audit events | `AuditService`, `AuditRecord` |
| `externaldata` | Providers, normalized observations, enrichment, context status | `GeocodingProvider`, `UrbanDataProvider`, `IncidentEnrichmentService` |
| `notifications` | In-app notifications (P1) | `NotificationService` |
| `geospatial` | Districts, assets, spatial resolution (P1) | `DistrictResolver`, `UrbanAssetResolver` |
| `shared` | Generic types only: errors, pagination, clock, correlation | `ApiError`, `PageResponse`, `ClockConfig` |

`incidents` owns attachments even though `StorageService` is an infrastructure boundary. Do not create a separate `evidence` module in P0; it adds cross-module calls without a demonstrated need.

Module dependency direction:

```text
identity <- incidents -> audit
                ^
                |
          externaldata -> (providers)
                |
          geospatial (P1)

notifications listens to incidents/identity events
analytics/assistance are P2 and must call public APIs, never internals
```

No cycles. `incidents` does not depend on `externaldata` or `notifications`. Since Modulith was removed, nothing in the build detects a cycle or an internal-type leak: state the dependencies you added in the completion report so the reviewer can check them.

### 4.3 Events

| Event | Publisher | Consumers | Timing |
|---|---|---|---|
| `IncidentCreated` | `incidents` | `externaldata`, `notifications`, analytics (P2) | After commit |
| `IncidentStatusChanged` | `incidents` | `notifications`, analytics (P2) | After commit |
| `IncidentAssigned` | `incidents` | `notifications` | After commit |
| `CommentAdded` | `incidents` | `notifications` | After commit |

"After commit" means `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` (section 3.2). Test it with an integration test that commits; a listener that never fires in a test that rolls back is expected behavior, not a bug.

Atomic work (incident row, status history, audit row, assignment row) uses direct in-process calls inside one transaction. Events never replace that.

### 4.4 Data model

All tables use UUID primary keys and UTC timestamps. Cross-module foreign keys and JPA relationships follow D0 and the class diagram (section 3.2).

This table was written **without the team's class diagram**. Before B01/B02 (D12), reconcile it with the diagram: the diagram wins on entity names, attributes and relationships; the invariants below (nullable category, append-only history and audit, provenance fields, observed vs ingested time) win over the diagram if they conflict, and the conflict is reported to the team.

| Table | Key fields |
|---|---|
| `users` | `id`, `email` unique, `password_hash`, `display_name`, `role`, `enabled`, `created_at`, `updated_at` |
| `refresh_tokens` | `id`, `user_id`, `token_hash`, `family_id`, `expires_at`, `revoked_at`, `replaced_by`, `created_at` |
| `categories` | `id`, `code` unique, `name`, `active` |
| `departments` | `id`, `code` unique, `name`, `active` |
| `incidents` | `id`, `title`, `description`, `category_id` nullable, `latitude`, `longitude`, `location` generated geography, `address`, `location_accuracy_m`, `status`, `priority` nullable, `priority_justification`, `priority_origin`, `reporter_id`, `current_department_id`, `current_assignee_id`, `context_status`, `last_enrichment_at`, `created_at`, `updated_at`, `version` |
| `status_changes` | `id`, `incident_id`, `from_status`, `to_status`, `actor_id`, `reason`, `public_note`, `internal_note`, `occurred_at` |
| `assignments` | `id`, `incident_id`, `department_id`, `technician_id` nullable, `assigned_by`, `assigned_at`, `ended_at`, `active` |
| `comments` | `id`, `incident_id`, `author_id`, `body`, `visibility`, `created_at` |
| `attachments` | `id`, `incident_id`, `storage_key` unique, `original_filename`, `detected_content_type`, `size_bytes`, `sha256`, `uploaded_by`, `created_at` |
| `audit_events` | `id`, `occurred_at`, `actor_id` nullable, `action`, `entity_type`, `entity_id`, `before_state` jsonb, `after_state` jsonb, `reason`, `trace_id` |
| `external_observations` | `id`, `incident_id` nullable, `type`, `source`, `source_url`, `observed_at`, `ingested_at`, `status`, `quality`, `payload` jsonb, `error_code`, `error_message`, `expires_at` |
| `notifications` (P1) | `id`, `user_id`, `type`, `title`, `body`, `entity_type`, `entity_id`, `read_at`, `created_at` |
| `districts` / `urban_assets` (P1) | `id`, `source`, `external_id`, `name`, `geometry`, metadata, `fetched_at` |

Location design:

```sql
latitude         numeric(9,6)  not null,
longitude        numeric(9,6)  not null,
location         geography(Point,4326)
                 generated always as (
                   ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
                 ) stored,
location_accuracy_m numeric(8,2)
```

- Latitude is `[-90, 90]`; longitude is `[-180, 180]`. Validate in the service and with database checks.
- Longitude is X, latitude is Y. Test with a known point in Málaga.
- Index `location` with GIST. Use `ST_DWithin` and `ST_Intersects` in native queries.
- If PostgreSQL rejects the generated expression, use a trigger to maintain `location`; document the fallback and keep tests.
- JPA maps `latitude`/`longitude` as plain numbers and does **not** map `location` (or maps it read-only). Spatial predicates go in native queries. This avoids adding `hibernate-spatial`; propose that dependency only if a task really needs geometry types in Java.

### 4.5 API conventions

- Base path `/api/v1`.
- JSON only, except multipart upload and file download.
- IDs are UUIDs; timestamps are ISO-8601 UTC strings.
- Errors are `application/problem+json` with:
  - `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, and optional `errors[]`.
- Pagination: `page` is 0-based, `size` default 20, maximum 100. Response:
  ```json
  {
    "items": [],
    "page": 0,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0
  }
  ```
- Sorting whitelist only. Default `createdAt,desc`; tiebreak by `id`.
- Status codes: `201` create, `200` read/action, `204` logout/delete, `400` validation, `401` unauthenticated, `403` authenticated but forbidden, `404` not found or hidden by scope, `409` conflict/invalid transition, `413` payload too large, `429` rate limited.
- `GET` endpoints never change state. Actions use `POST` with an explicit reason where required.
- `allowedActions` is computed server-side in incident detail and returned to clients. Clients must not duplicate the transition matrix.

### 4.6 Authentication and authorization

Token model:

| Element | Decision |
|---|---|
| Access token | JWT, HS256, 15 minutes, `Authorization: Bearer` |
| Refresh token | Opaque 256-bit random, 30 days, SHA-256 hash in DB, rotated on every refresh |
| Web refresh | `HttpOnly` cookie `urbanpulse_refresh`, `SameSite=Strict`, `Secure` on HTTPS |
| Mobile refresh | Returned in the response only when `X-Client: mobile`; stored with SecureStore |
| Logout | Revoke refresh token, clear cookie, delete mobile token |
| Password | BCrypt strength 12; generic login error |
| Login limit | P0: simple in-memory per-email/IP window; 429 after repeated failures |
| JWT secret | `JWT_SECRET`, at least 32 bytes; fail fast outside dev/test |
| Claims | `sub`, `roles`, `iss`, `aud`, `iat`, `exp`, `jti` |

- Web stores the access token in memory and uses `credentials: 'include'` for refresh/logout. Do not use `localStorage` for tokens.
- On a 401, the API client performs one single-flight refresh and retries the original request once. If refresh fails, clear the session and redirect to login.
- Cookie-based refresh/logout must validate the request origin against `APP_ORIGIN`. Do not add wildcard CORS.
- The web cookie is not in ADR-0004 (which values a model "without relying on browser cookie behaviour"). It is implemented only after D4 is recorded as an amendment to ADR-0004.
- `ADMIN` includes operator permissions. P0 uses one primary role per user; document this simplification. Multi-role support is P1.
- Role names: this guide says `CITIZEN`; ADR-0004 says `user`. Use the name approved in D3 everywhere (code, OpenAPI, seed, docs) and let the team align the ADR text.
- Disabling a user (`enabled = false`) revokes all their refresh tokens, as ADR-0004 requires; their access token stays valid only until it expires.
- Role checks are enforced in the service layer with `@PreAuthorize` and explicit ownership/assignment checks.

Permission matrix (proposal, requires approval before B01/B04):

| Action | Citizen | Operator | Technician | Admin | Analyst |
|---|---|---|---|---|---|
| Register/login/refresh/logout | yes | yes | yes | yes | yes |
| Create incident | yes | yes | no | yes | no |
| Read own incident | yes | yes | assigned | yes | aggregate only |
| Read all incidents | no | yes | no | yes | aggregate only |
| Update own report while `REPORTED` | yes | no | no | no | no |
| Upload evidence to own incident | yes | yes | assigned | yes | no |
| Validate/reject/request information | no | yes | no | yes | no |
| Set priority | no | yes | no | yes | no |
| Assign/reassign/unassign | no | yes | no | yes | no |
| Start/resolve | no | no | assigned | no | no |
| Close | no | yes | no | yes | no |
| Reopen own resolved incident | yes | yes | no | yes | no |
| Internal notes | no | yes | yes | yes | no |
| Public comments | own | all | assigned | all | no |
| Categories/departments/users admin | no | no | no | yes | no |
| Statistics/export | no | basic | no | yes | aggregate |
| Audit | own public history | incident history | assigned history | full | no |

### 4.7 Status transition matrix

This is a **proposal**; the human must approve it before B04. Use `IN_PROGRESS` in code and document its correspondence with the assignment's `IN PROGRESS`.

| From | To | Actor | Required data |
|---|---|---|---|
| `REPORTED` | `VALIDATED` | Operator/Admin | none |
| `REPORTED` | `REJECTED` | Operator/Admin | reason |
| `VALIDATED` | `ASSIGNED` | Operator/Admin | department and/or technician |
| `VALIDATED` | `REJECTED` | Operator/Admin | reason |
| `ASSIGNED` | `IN_PROGRESS` | Assigned technician | none |
| `IN_PROGRESS` | `RESOLVED` | Assigned technician | resolution note |
| `RESOLVED` | `CLOSED` | Operator/Admin | none |
| `RESOLVED` | `REOPENED` | Owner citizen, Operator, Admin | reason |
| `REOPENED` | `ASSIGNED` | Operator/Admin | department and/or technician |
| `REOPENED` | `IN_PROGRESS` | Assigned technician | none |
| `REJECTED` | terminal | — | — |
| `CLOSED` | terminal | — | — |

- Reassignment while `ASSIGNED` or `IN_PROGRESS` keeps the status, ends the previous active assignment, creates a new one, and is audited.
- Unassign returns the incident to `VALIDATED` only when it is `ASSIGNED`; otherwise reject the action.
- Request information does not change status; it adds a public comment and audit event.
- No skipping states. No generic "set status" endpoint that bypasses the matrix.
- Invalid transition: `409` with `INCIDENT_INVALID_TRANSITION`; no status row, no audit row, no partial update.

Open points the matrix leaves ambiguous. D2 must settle them before B04/B05; the recommended answer is in brackets:

1. Can an incident assigned only to a department be started? [No: `start` requires an assigned technician; a department-only assignment stays `ASSIGNED` until a technician is added.]
2. Reassigning while `IN_PROGRESS`: keep `IN_PROGRESS` or go back to `ASSIGNED`? [Back to `ASSIGNED`: the new technician has not started the work.]
3. Does the active assignment end at `RESOLVED`? [No, it ends at `CLOSED`, so `REOPENED → IN_PROGRESS` by the same technician works.]
4. Can `REOPENED` go to `CLOSED` or `REJECTED`? [`CLOSED` with reason by operator/admin, for unjustified reopenings; never `REJECTED`.]
5. In which states can priority be set? [Any non-terminal state from `VALIDATED` on; not in `REPORTED`, `REJECTED` or `CLOSED`.]
6. Automatic closing after N days? [Not in this delivery.]

### 4.8 External context model

`Incident.contextStatus`:

- `PENDING`: creation committed, enrichment not finished or not started.
- `AVAILABLE`: all requested observations are current.
- `PARTIAL`: some observations are available and others failed or are unavailable.
- `UNAVAILABLE`: the source failed and no usable observation exists.
- `STALE`: the available observation is older than its validity window.

Observation fields: `source`, `sourceUrl`, `type`, `observedAt`, `ingestedAt`, `status`, `quality`, `payload`, `errorCode`, `errorMessage`, `expiresAt`.

P0 enrichment:

1. `IncidentCreated` is published after commit.
2. `externaldata` reverse-geocodes the address with Nominatim.
3. It stores `ExternalObservation` rows.
4. It calls `incidents` public context API to update address/context status.
5. If the provider fails, the incident remains valid and context becomes `UNAVAILABLE` or `PARTIAL`.
6. An operator can request a retry; the request is `202 Accepted`.

P1 adds district/asset resolution with OpenData Málaga primary and OSM fallback. Do not invent polygons, assets, or confidence.

### 4.9 Frontend architecture

- `packages/domain`: runtime enums, labels, formatters, Zod schemas for client forms. Do not duplicate server transition logic.
- `packages/api-client`: types generated from `docs/api/openapi.yaml` with `openapi-typescript`; wrapper with base URL, bearer token provider, single-flight refresh, `ProblemDetail` error mapping, optional credentials for web.
- `packages/ui`: CSS variables/design tokens and tiny platform-agnostic pieces; no web-only React components in the shared package.
- `apps/web`: Vite + React + TypeScript, React Router, TanStack Query, CSS Modules, MapLibre GL JS. No component library.
- `apps/mobile`: Expo + React Native + TypeScript, Expo Router, SecureStore, Expo Location, Expo ImagePicker, MapLibre only if a dev build is viable.
- No client calls to Nominatim/Overpass. Use the backend proxy endpoints.
- Every screen handles loading, empty, error, retry, success, and duplicate-submit prevention.
- Accessibility: labels, keyboard/focus, contrast, touch targets, screen-reader text.
- Mobile API URL is configurable; `localhost` is not valid from a device/emulator.

### 4.10 Environment variables

Add these to `urbanpulse/.env.example` and/or root `.env.example` as the task requires. Never commit real values.

| Variable | Used by | Purpose |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` | Compose/dev | Existing database configuration |
| `SPRING_PROFILES_ACTIVE` | Backend | `dev`, `test`, `prod` |
| `SERVER_PORT` | Backend | HTTP port |
| `JWT_SECRET` | Backend | At least 32 bytes; required outside dev/test |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Backend | Token validation |
| `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL` | Backend | Durations |
| `APP_ORIGIN` | Backend | Allowed origin for cookie refresh/logout |
| `STORAGE_BASE_PATH` | Backend | Evidence directory |
| `EVIDENCE_MAX_SIZE` | Backend | Maximum upload bytes |
| `NOMINATIM_BASE_URL`, `OVERPASS_BASE_URL`, `OPENDATA_BASE_URL` | Backend | Provider endpoints |
| `EXTERNAL_CONTACT_EMAIL` | Backend | Nominatim User-Agent contact |
| `EXTERNAL_HTTP_TIMEOUT`, `EXTERNAL_HTTP_RETRIES` | Backend | Timeouts and bounded retries |
| `VITE_API_URL` | Web | Default `/api/v1` |
| `VITE_MAP_STYLE_URL`, `VITE_MAP_TILE_URL` | Web | Map configuration and attribution |
| `EXPO_PUBLIC_API_URL` | Mobile | Reachable backend URL |
| `HTTP_PORT`, `BACKEND_PORT` | Compose | Published ports if D01 is executed |

Spring Boot does not read `.env`; only Docker Compose does. Developers load `.env` into the IntelliJ run configuration, and `SPRING_PROFILES_ACTIVE=dev` must be set there once F01 adds profiles. Every variable that is mandatory in the default profile must also be exported in the CI smoke-test step (section 0.4).

### 4.11 Testing strategy

| Layer | Tool | Required coverage |
|---|---|---|
| Backend unit | JUnit 5 | Status matrix, priority, validation, permissions, domain invariants |
| Backend integration | Spring Boot + Testcontainers PostGIS | Migrations, transactions, PostGIS, storage, auth flows |
| Backend HTTP | MockMvc | Status codes, errors, pagination, serialization, authorization |
| Web unit/component | Vitest + Testing Library + MSW | Auth, forms, error/empty/loading, filters |
| Web E2E | Playwright + axe | Citizen-to-resolution flow, operator flow, accessibility smoke |
| Mobile | Jest + React Native Testing Library | Auth, form validation, permission-denied paths |
| Mobile manual | Device/emulator checklist | GPS, camera, upload, refresh, offline/error |

No real provider calls in automated tests. Use fakes, fixtures, or `MockRestServiceServer`.

### 4.12 Definition of done for every task

- Code compiles and follows the module boundaries.
- Tests cover the new behavior and the relevant failure/permission cases.
- OpenAPI is updated and validates.
- A new versioned migration exists if the schema changed; applied migrations are never edited.
- `.env.example` is updated if a new variable exists.
- `README.md`/task-specific docs and `CHANGELOG.md` are updated when behavior changes.
- Formatting and static analysis pass.
- No secrets, no unrelated changes, no dead code, no commented-out code.
- The task report includes actual commands and results.

---

## 5. Decisions that require human approval

These are **proposals**, not accepted decisions. Do not implement a dependent task until the human confirms that the team approved the relevant item (`AGENTS.md` section 1: decisions that change the domain, permissions, contract or architecture are team decisions).

Where approvals are recorded:

- Decisions that change an ADR or the architecture (D0, D2, D4, D10, D11) go in an ADR (new, or amendment of the existing one) or in `AGENTS.md`, in the P00 documentation PR. A PR description is not enough: the next session will not read it.
- Delivery-only choices (D5–D9) can be recorded in the task prompt or the PR description.
- You may draft those ADRs as `Proposed` when asked; only the team changes a status to `Accepted`.

### 5.1 Decision table

| ID | Decision | Recommended default | Needed before |
|---|---|---|---|
| D0 | Module rules after removing Spring Modulith (`6a2c356`) | Amend ADR-0010: keep package-by-feature modules and the transaction/event rules; drop `@ApplicationModule`, `ModularityTest`, `DocumentationTest`; allow the JPA relationships defined in the class diagram; IDs elsewhere; boundaries checked in review | F01 |
| D1 | Repository layout | Keep `urbanpulse/`; add `apps/*` and `packages/*` | F01/C01 |
| D2 | Status matrix | Section 4.7; `REJECTED` and `CLOSED` terminal | B04 |
| D3 | Roles | One primary role per user; `ADMIN` includes operator actions | B01 |
| D4 | Token transport | Access in memory; web refresh cookie; mobile SecureStore | B01 |
| D5 | External cache | In-process TTL cache; Redis only if ADR-0012 is accepted | B07 |
| D6 | Mobile map | P0 coordinate picker/static OSM preview; MapLibre embedded P1 | M02 |
| D7 | Deployment | Docker/nginx P1; promote to P0 only if the rubric requires it | D01 |
| D8 | AI/RAG | P2 and explicitly out of the one-week delivery; no fake AI | B12 |
| D9 | Evidence policy | JPEG/PNG/WebP/PDF, 10 MB, magic-byte validation | B03 |
| D10 | Migration tool | Flyway, new ADR. In Spring Boot 4 the auto-configuration is in its own module: use `spring-boot-starter-flyway` plus `flyway-database-postgresql`, versions from the Boot parent (verify against the Boot 4.1 documentation; `flyway-core` alone does not run migrations) | F01 |
| D11 | Which client each actor uses | Follow the issue template: mobile = citizen (P0); web = operator/admin/analyst; technician on web in P0 as a documented exception, mobile technician P1; W02 becomes P1 | W01/M01 |
| D12 | Data model source | Add the team's class diagram to the repository (for example `docs/architecture/class-diagram.*`); reconcile section 4.4 with it | B01 |

If D11 keeps the default: W02 moves to P1; W03 depends on W01 and B06 instead of W02; Q01 creates the citizen report through the API (or the mobile app in the manual checklist); the demo runs the citizen part on mobile.

D3 also fixes the role names (`CITIZEN` or ADR-0004's `user`). D2 also answers the open points listed after the matrix in section 4.7.

If the human does not answer, implement only what the task card says and leave the dependent behavior unimplemented with a clear report.

---

## 6. Task catalog

### 6.1 Dependency order

```text
P00 -> F01 -> B01 -> B02 -> B03
                    |       |
                    |       +-> C01 -> W01 -> W02 -> W03 -> W04 -> Q01
                    |                 |                 ^
                    |                 +-> M01 -> M02 ---+
                    +-> B04 -> B05 ---+
                    +-> B06 ----------+
                    +-> B07 -> B08 (P1)
                    +-> B09 (P1, after B06)
                    +-> B10 (P1, after B04/B05)
                    +-> B11/B12 (P2)
F02 after B07
D01 after F02/C01/W01 (P1)
D02 last
```

With D11's default, W02 is P1 and W03 hangs from W01 + B06.

Parallel work. The team has several people, and the chain above is longer than one person's week. After B02, these tracks touch different files and can run in parallel, each in its own branch: (a) B03 → C01 → clients, (b) B04 → B05, (c) B06, (d) B07. Rules for parallel tracks:

- Each task keeps the migration number reserved in section 9.2, even if it merges out of order. Flyway rejects a lower version that arrives after a higher one was applied: merge in number order when possible; otherwise the developer recreates the local database (never a shared one). Do not enable `out-of-order` without a team decision.
- `docs/api/openapi.yaml` and `CHANGELOG.md` are touched by every task: keep edits local to your paths/entries and rebase before the PR.
- Two tracks never edit the same entity or service class at the same time; if a card needs that, stop and report.

### 6.2 Task table

| ID | Pri. | Timebox | Depends on | RF | Outcome |
|---|---|---|---|---|---|
| P00 | P0 | 2–3 h | — | — | Human setup, baseline, decisions, branch |
| F01 | P0 | 3 h | P00 | foundation | Build/config/migrations/errors/modules/OpenAPI spine |
| B01 | P0 | 4 h | F01 | RF01–02 | Users, roles, JWT, refresh, permissions |
| B02 | P0 | 4 h | B01 | RF03–04, RF06 | Incident create/read/update, location, optional category |
| B03 | P0 | 3 h | B02 | RF05 | Evidence upload/download and storage |
| B04 | P0 | 4 h | B02 | RF09–10, RF12, RF18 | Lifecycle, priority, history, audit |
| B05 | P0 | 3 h | B04 | RF11, RF13 | Assignment, comments, internal notes |
| B06 | P0 | 3 h | B02 | RF06–08 | Filters, pagination, map bbox |
| B07 | P0 | 4 h | B02 | RF23–24, RF26 | External context and degradation |
| B08 | P1 | 3 h | B07 | RF21–22 | Districts, neighborhoods, assets |
| B09 | P1 | 2 h | B06 | RF16 | Duplicate suggestions |
| B10 | P1 | 2 h | B04–B05 | RF14 | In-app notifications |
| B11 | P2 | 4 h | B06 | RF15, 17, 19 | Statistics, zonal analysis, reports |
| B12 | P2 | 4 h | B02, B07 | RF20, 25 | AI assistance/correlation, no fake output |
| C01 | P0 | 3 h | B03 | clients | pnpm workspace, domain, generated API client, UI tokens |
| W01 | P0 | 3 h | C01, B01 | RF01–02 | Web shell, auth, session refresh, routes |
| W02 | P0 (P1 if D11 default) | 4 h | W01, B03, B07 | RF03–06 | Citizen report, evidence, tracking |
| W03 | P0 | 3 h | W02 (W01 if D11 default), B06 | RF07–08 | Operator queue, filters, map |
| W04 | P0 | 4 h | W03, B04–B05 | RF09–13, 18, 24 | Operator detail, actions, audit, context |
| W05 | P1 | 3 h | W04 | RF14–15, 17, 19 | Admin/analyst UI |
| M01 | P0 | 3 h | C01, B01 | RF01–02 | Expo shell, auth, SecureStore |
| M02 | P0 | 4 h | M01, B03, B07 | RF03–06, 08 | Mobile report, evidence, tracking |
| M03 | P1 | 3 h | M02 | RF08, 14 | Embedded map, push, polish |
| Q01 | P0 | 3 h | W02, W04, M02 | critical cases | Playwright, accessibility, mobile checklist |
| F02 | P0 | 2 h | B07 | backend gate | Seed, hardening, OpenAPI final, backend verification |
| D01 | P1 | 3 h | F02, C01, W01 | deployment | Dockerfiles, compose, nginx, healthchecks |
| D02 | P0 | 3 h | all P0 | docs/CI | CI web/mobile, docs, ADRs, demo, limitations |

### 6.3 Contract freezes

- After B03: auth, incidents, and evidence contract is frozen for C01/W01/W02/M01/M02.
- After B06: list/map/action contract is frozen for W03/W04.
- After B07: context contract is frozen for W04/M02.
- Any contract change after a freeze requires human approval, an OpenAPI update, client regeneration, and a note in the report.

---

## 7. Task cards

Each card is a separate unit of work. Do not execute two cards in one session.

---

### P00 — Human setup and baseline

**Priority:** P0. **Depends on:** none. **Timebox:** 2–3 h. **Owner:** human, with read-only AI assistance.

**Goal:** start from a green, understood baseline.

**Checklist:**

- Install Java 25, Docker Desktop/Engine, Git, Node 22 LTS, pnpm via Corepack, and an Expo-capable environment.
- Start Docker. Copy `urbanpulse/.env.example` to `urbanpulse/.env`; set a local password.
- Run `./mvnw.cmd verify` in `urbanpulse/` (Windows) or `./mvnw verify` (Linux/macOS). Record the result.
- If `main` is behind `boilerplate`, open a PR from `boilerplate` to `main`. **In that PR, before merging**, remove `boilerplate` from the triggers of `ci.yml` and `pr-title.yml`, as their `TEMPORARY` comments say. Let CI pass, and squash merge. Do not develop directly on `boilerplate`.
- Documentation-only PR (`docs: ...`), AI-assisted if wanted: add `docs/guias/guia-ia.md` and `docs/guias/guia-persona.md`; add the class diagram (D12); draft the decision records for D0, D2, D4, D10 and D11 as ADRs/amendments with status `Proposed`; remove the stale Modulith/`ModularityTest` references from `README.md`, `docs/ci.md`, `docs/ci-setup.md` and ADR-0015 (the ADR-0010 change goes through D0). The team accepts the ADRs; the AI never sets `Accepted`.
- Create a branch from updated `main`: `feature/f01-delivery-spine`.
- Approve or reject decisions D0–D12 in section 5.
- Create P0 issues (optional but recommended) with the feature template, RF label, and milestone.
- Read `docs/guias/guia-persona.md` section 2 and complete the decision table.

**Output:** a green baseline, an updated `main` without `boilerplate` triggers, the guides and class diagram in the repository, recorded decisions, and a task branch.

**Do not:** start F01 while the baseline is red or a blocking decision is unresolved.

---

### F01 — Delivery spine

**Priority:** P0. **RF:** foundation. **Depends on:** P00. **Timebox:** 3 h.

**Read first:** `AGENTS.md` sections 3–6 and 9; ADR-0008, ADR-0010 (with the D0 amendment), ADR-0011, ADR-0015, the D10 decision record; `docs/ci.md` (the `build` smoke test); `urbanpulse/pom.xml`; `application.yaml`; `SecurityConfig`; `TestcontainersConfiguration`; `UrbanpulseApplicationIT`.

**Goal:** make the backend ready for feature work without implementing a feature.

**In scope:**

- Add backend dependencies: validation and Flyway as approved in D10 (`spring-boot-starter-flyway` + `flyway-database-postgresql` in Boot 4; versions from the parent). Do **not** add Spring Modulith (removed in `6a2c356`).
- Springdoc: not by default. `docs/api/openapi.yaml` is the hand-written source of truth (ADR-0008 contract-first); a generated spec would be a second, diverging contract. Propose Springdoc only if the team wants Swagger UI, serving the static file.
- Configure `default`, `dev`, `test`, and `prod` profiles. Externalize configuration. Keep `ddl-auto: validate`, `open-in-view: false`, UTC, and structured logs. The default profile must still start in the CI smoke test with only the variables it exports.
- Add `V1__enable_postgis.sql`; establish migration naming and ownership. Do not create domain tables yet.
- Add a `Clock` bean, correlation-ID filter, RFC 9457 error handling, validation error mapping, and pagination records in package `shared`.
- Do not create empty module packages (`AGENTS.md`: no empty folders). `identity`, `incidents`, `audit` and the rest appear in the task that first puts code in them.
- Create `docs/api/openapi.yaml` with `info`, servers, security schemes, common `ProblemDetail`, pagination, auth, and incident path placeholders. Add a lint step with a **pinned** Redocly version (`npx @redocly/cli@<exact version> lint`) to the existing `lint` job, without renaming the job.
- Update `CHANGELOG.md` only if repository behavior/tooling changes.

**Out of scope:** auth endpoints, incident domain, attachments, clients, Docker, Redis, AI, admin CRUD, notifications.

**Design constraints:**

- Module conventions follow section 3.2 (no Modulith annotations or tests).
- No `ddl-auto: update`.
- No new component (Redis, broker, cache) without an accepted decision.
- Do not reformat unrelated Java files.

**Deliverables:**

- Updated `pom.xml`, `application.yaml`, profile files if needed.
- `src/main/resources/db/migration/V1__enable_postgis.sql`.
- `shared` error/pagination/clock/correlation code.
- `docs/api/openapi.yaml` and the pinned lint step in `ci.yml`.
- Tests for error handling, pagination serialization, and migration/PostGIS availability.

**Acceptance criteria:**

- `./mvnw.cmd verify` passes with Docker running.
- The application starts with the existing Compose database.
- Flyway applies `V1`; `postgis` extension exists.
- The packaged jar still passes the CI smoke test (start with env vars only, `/actuator/health` UP, clean SIGTERM).
- OpenAPI lint passes.
- No domain tables, endpoints or empty module packages are invented.
- No Spring Modulith dependency, annotation or test is reintroduced.
- No secrets are committed.

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

```bash
npx @redocly/cli@<pinned version> lint docs/api/openapi.yaml
```

**Stop/report:** if D0 or D10 is not recorded, stop before adding dependencies. If Flyway cannot apply because the database volume has an incompatible state, do not delete the developer's volume. Report the exact error and propose a safe reset command.

---

### B01 — Identity, authentication, and authorization

**Priority:** P0. **RF:** RF01–02. **Depends on:** F01. **Timebox:** 4 h.

**Read first:** `AGENTS.md` sections 3, 6, 8, 11; ADR-0004 (with the D4 amendment), ADR-0010 (with D0); the class diagram (D12) for `User`; current `SecurityConfig`; `pom.xml` JWT dependencies; D3/D4 decisions; `docs/ci.md` on mandatory properties in the smoke test.

**Goal:** replace HTTP Basic with self-managed JWT authentication and server-side role checks.

**In scope:**

- `users` and `refresh_tokens` migrations.
- `User` domain, role enum, password hashing (BCrypt 12), enabled flag.
- `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout`; `GET /api/v1/users/me`.
- Access JWT (15 min) with `sub`, `roles`, `iss`, `aud`, `iat`, `exp`, `jti`; refresh token rotation with reuse/revocation behavior.
- Web refresh cookie and mobile refresh response (`X-Client: mobile`); origin check for cookie refresh/logout.
- Stateless Spring Security filter chain; public register/login/refresh/health; authenticated everything else; dev-only docs.
- `@PreAuthorize` and service-level checks. `ADMIN` includes operator permissions.
- Simple in-memory login rate limit (P0) with a controllable clock.
- Disabling a user revokes all of their refresh tokens (ADR-0004).
- Role names exactly as approved in D3.
- If `JWT_SECRET` (or any other new property) is mandatory in the default profile, export a throwaway value in the smoke-test step of the `build` job in `ci.yml`, next to `SMOKE_PASSWORD`, so `build` stays green.
- Audit failed/successful sensitive auth actions only if the audit module is already usable; otherwise record a clear open item in the task report, not in production code comments.

**Out of scope:** incident features, admin user CRUD (P1), social login, email verification, password reset, Redis rate limiting, multi-role support.

**Design constraints:**

- Never return password hash, refresh token hash, or internal user entity.
- Generic login failure message; do not reveal whether an email exists.
- JWT secret from environment; no default production secret.
- Refresh token stored only as SHA-256 hash; rotate and revoke previous token.
- Cookie: `HttpOnly`, `SameSite=Strict`, `Secure` when HTTPS, path `/api/v1/auth`.
- No wildcard CORS.
- No tokens in logs.

**Deliverables:**

- `identity` module implementation, security filter/config, migrations, DTOs/mappers, tests, OpenAPI auth paths, `.env.example` updates.
- `CHANGELOG.md` entry.

**Acceptance criteria:**

- Register returns `201`; duplicate email returns `409`; invalid input returns `400`.
- Login returns an access token and sets the web cookie; mobile gets a refresh token with `X-Client: mobile`.
- Refresh rotates the token and rejects a reused/revoked token.
- Logout revokes the token and clears the cookie.
- A disabled user cannot refresh; their refresh tokens are revoked.
- The CI `build` smoke test still starts the jar and `/actuator/health` stays public.
- Expired/invalid access returns `401`; insufficient role returns `403`.
- A citizen cannot call operator-only endpoints.
- No sensitive data appears in JSON or logs.
- Unit/integration tests cover success, failure, rotation, revocation, expiry, and role checks.

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: register, login, call `/users/me`, refresh, logout, reuse the old refresh token and observe rejection.

**Stop/report:** if cookie behavior differs between `localhost` HTTP and production HTTPS, document it and keep `Secure` conditional. Do not weaken `HttpOnly` or `SameSite`.

---

### B02 — Incident registration and consultation

**Priority:** P0. **RF:** RF03–04, RF06. **Depends on:** B01. **Timebox:** 4 h.

**Read first:** `AGENTS.md` sections 2–3; ADR-0001, ADR-0003, ADR-0009, ADR-0010 (with D0); the class diagram (D12) for `Incident`, `Category` and their relationships; approved D1/D2.

**Goal:** create, read, list, and update the core incident report with PostGIS location and optional category.

**In scope:**

- `categories` and `incidents` migrations; seed stable category reference data in a migration.
- Incident entity with generated `location geography(Point,4326)`, lat/lon validation, optional category, status `REPORTED`, nullable priority, reporter ID, context status `PENDING`, version.
- `POST /api/v1/incidents`, `GET /api/v1/incidents` (role-scoped), `GET /api/v1/incidents/{id}`, `PATCH /api/v1/incidents/{id}` for the owner while `REPORTED`.
- `GET /api/v1/categories`.
- Citizen list only own incidents; operator/admin list all; technician list assigned (assignment arrives in B05; until then return an empty/limited result with a clear rule, not a security hole).
- DTO validation, stable pagination, UTC timestamps, no entity exposure.
- PostGIS integration tests for latitude/longitude order and distance/filter basics.

**Out of scope:** lifecycle actions, evidence, assignment, comments, filters beyond basic pagination, bbox/map (B06), external context (B07), duplicate suggestions.

**Design constraints:**

- Category is optional. Do not add a `NOT NULL` constraint.
- Location is required for creation. Preserve accuracy when supplied.
- Use UUIDs and `@Version`.
- Do not expose `reporter_id` as an entity or internal fields.
- The client cannot set `status`, `priority`, `reporterId`, `contextStatus`, or audit fields.

**Deliverables:**

- `incidents` module core, migrations, DTOs/mappers, repository/controller/service, tests, OpenAPI incident paths, `CHANGELOG.md`.

**Acceptance criteria:**

- Create with category and without category both return `201`.
- Latitude/longitude out of range or swapped returns `400`; a known Málaga point returns the expected coordinates from PostGIS.
- A citizen cannot read another citizen's incident: `404`, following the hidden-resource rule in section 3.3.
- Operator/admin can read all; the scope is enforced by tests.
- Update is allowed only for the owner and only while `REPORTED`.
- Pagination is stable and capped; no unbounded list.
- No entity or internal field leaks.

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: create with/without category via HTTP, query the row with `ST_X`/`ST_Y`, and attempt cross-user access.

**Stop/report:** if the generated geography column cannot be created in PostgreSQL, do not silently drop to plain lat/lon. Use a trigger, document why, and keep the PostGIS contract.

---

### B03 — Evidence attachments

**Priority:** P0. **RF:** RF05. **Depends on:** B02. **Timebox:** 3 h.

**Read first:** `AGENTS.md` section 8; ADR-0013 (Proposed); approved D9.

**Goal:** allow authorized evidence upload and download without storing binaries in PostgreSQL.

**In scope:**

- `attachments` migration; `StorageService` interface and `LocalFsStorage`.
- `POST /api/v1/incidents/{id}/attachments` multipart upload; `GET /api/v1/incidents/{id}/attachments`; `GET /api/v1/incidents/{id}/attachments/{attachmentId}` download.
- 10 MB limit, JPEG/PNG/WebP/PDF magic-byte validation, SHA-256, detected content type, UUID object keys, atomic write.
- Authorization: owner while allowed, operator/admin, assigned technician.
- Safe download headers; no public URL; no user-controlled path.
- Cleanup on metadata failure; tests with a temporary directory.
- Set `spring.servlet.multipart.max-file-size`/`max-request-size` from `EVIDENCE_MAX_SIZE` (the Spring default is 1 MB) and map `MaxUploadSizeExceededException` to the `ATTACHMENT_TOO_LARGE` problem response.

**Out of scope:** thumbnails, virus scanning, video, image processing, object storage/S3, public links, evidence deletion (P1).

**Design constraints:**

- Do not store the binary in a `bytea` column.
- Do not trust filename extension or declared `Content-Type`.
- Do not log file contents or full user paths.
- Store metadata only after a successful write; delete the file if the DB transaction fails.
- Use the storage key, never the original filename, to resolve the file.

**Deliverables:**

- `attachments` table, storage package, endpoints, DTOs, tests, OpenAPI paths, `.env.example` entries, `CHANGELOG.md`.
- ADR-0013 status proposal in the report (do not change the ADR yourself without approval).

**Acceptance criteria:**

- Valid JPEG/PNG/WebP/PDF uploads return `201` with metadata.
- Oversized file returns `413`/`400` consistently; wrong magic bytes returns `400`.
- A user cannot upload to or download from an incident they cannot access.
- Download returns the stored bytes and safe headers.
- Filename/path traversal attempts cannot control the storage key.
- A failed metadata write leaves no orphan file (tested with a fake failure).

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: upload a valid image, a PDF, a renamed text file, and a file over the limit; download as owner and as a different citizen.

**Stop/report:** if the local storage path is outside the allowed workspace or the container path is unknown, report the configuration instead of guessing.

---

### B04 — Lifecycle, priority, and audit

**Priority:** P0. **RF:** RF09–10, RF12, RF18. **Depends on:** B02. **Timebox:** 4 h.

**Read first:** `AGENTS.md` section 3 lifecycle/invariants; ADR-0010 events and transactions; approved D2/D3.

**Goal:** centralize status transitions, justified priority, history, and audit in atomic transactions.

**In scope:**

- `status_changes` and `audit_events` migrations; `audit` module with append-only `AuditService`.
- Domain transition matrix in one place; explicit action methods/endpoints:
  - `POST /incidents/{id}/validate`
  - `POST /incidents/{id}/reject` (reason required)
  - `POST /incidents/{id}/request-information`
  - `POST /incidents/{id}/priority` (priority + justification required)
  - `POST /incidents/{id}/start`
  - `POST /incidents/{id}/resolve` (resolution required)
  - `POST /incidents/{id}/close`
  - `POST /incidents/{id}/reopen` (reason required)
- `GET /incidents/{id}/history` with role-filtered public/internal entries.
- `allowedActions` in incident detail, computed from the matrix, role, and assignment.
- Optimistic locking; invalid transition returns `409` and leaves no partial history.
- Audit rows with actor, time, reason, before/after (redacted), and trace ID.
- Public timeline excludes internal notes; citizen sees only public history.

**Out of scope:** assignment mechanics/comments (B05), notifications, AI priority, generic status-setting endpoint, bulk actions.

**Design constraints:**

- Status update, status history, and audit insert must share one transaction. Do not use an event for these.
- Reject/resolve/reopen require a reason/resolution. Priority requires justification and origin `MANUAL`.
- Do not let a caller skip states.
- `ADMIN` may perform operator actions but cannot bypass the matrix.
- Do not expose internal notes to citizens.

**Deliverables:**

- `audit` module, status/priority logic, migrations, endpoints, DTOs, tests, OpenAPI paths, `CHANGELOG.md`.

**Acceptance criteria:**

- Valid transition updates status, adds one history row, and one audit row in one commit.
- Invalid transition returns `409`, with no status/history/audit change.
- A stale version returns `409` and does not overwrite another actor's change.
- Reject without reason, resolve without resolution, and priority without justification are rejected.
- Citizen history hides internal notes; operator/technician history includes them as allowed.
- `allowedActions` matches the matrix and role for representative cases.
- Audit row records actor, time, reason, action, entity, and before/after.

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: validate, set priority, reject another incident, attempt an illegal transition, and inspect history/audit rows.

**Stop/report:** if the approved matrix is missing or ambiguous, stop before coding. Do not invent transitions.

---

### B05 — Assignment and collaboration

**Priority:** P0. **RF:** RF11, RF13. **Depends on:** B04. **Timebox:** 3 h.

**Read first:** `AGENTS.md` actors/invariants; ADR-0010; B04 matrix.

**Goal:** assign work to a department/technician and preserve history, while separating public and internal communication.

**In scope:**

- `departments`, `assignments`, `comments` migrations.
- `POST /incidents/{id}/assign` (department and/or technician), `POST /incidents/{id}/unassign`.
- Assignment sets `ASSIGNED` from `VALIDATED`/`REOPENED`; reassignment keeps status and closes the previous active assignment; unassign returns `ASSIGNED` to `VALIDATED`.
- Current assignment fields on the incident stay consistent with the active assignment row.
- `POST /incidents/{id}/comments`, `GET /incidents/{id}/comments` with `PUBLIC`/`INTERNAL` visibility.
- Request-information action from B04 creates a public comment plus audit if not already implemented there.
- Technician can start/resolve only when assigned; operator/admin can assign.
- `GET /departments`.

**Out of scope:** teams, multi-department routing, notifications, admin CRUD screens, workload balancing, auto-assignment.

**Design constraints:**

- Assignment history is append-only; never delete/overwrite the previous row.
- Internal comments never appear in citizen responses, even if the citizen guesses the endpoint.
- Assignment and status change are atomic with audit.
- Technician must exist, be enabled, and have the technician role; department must be active.
- References to users (technician, author, `assigned_by`) follow D0 and the class diagram; where the diagram is silent, store the UUID and validate it through the `identity` service.

**Deliverables:**

- Department/assignment/comment implementation, migrations, endpoints, DTOs, tests, OpenAPI, `CHANGELOG.md`.

**Acceptance criteria:**

- Operator assigns a validated incident; status becomes `ASSIGNED`; active assignment and current fields match.
- Reassignment closes the old assignment and preserves history.
- Unassign returns to `VALIDATED` only from `ASSIGNED`.
- Assigned technician can start; another technician cannot.
- Citizen sees public comments only; internal comments return `404`/`403` or are absent consistently.
- Assignment permissions and invalid targets are tested.

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: assign, reassign, unassign, start as the wrong technician, and read comments as citizen/operator.

---

### B06 — Search, filters, and map

**Priority:** P0. **RF:** RF06–08. **Depends on:** B02. **Timebox:** 3 h.

**Read first:** `AGENTS.md` section 6; ADR-0003; B02/B04/B05 DTOs.

**Goal:** make incidents discoverable through stable filters, pagination, and a bounded map query.

**In scope:**

- Extend `GET /incidents` with filters: status, category, priority, department, assignee, date range, text query, and optional bbox.
- `GET /api/v1/map/incidents?bbox=minLon,minLat,maxLon,maxLat` returning a minimal marker DTO.
- Role-scoped visibility: citizen own, technician assigned, operator/admin all; analyst aggregate only (P2).
- Whitelisted sort fields, default `createdAt,desc`, ID tiebreaker.
- Bbox validation and maximum area; return `400` for invalid/crossing antimeridian input.
- Indexes: GIST on `location`, B-tree on `status`, `created_at`, `category_id`, `current_assignee_id`.
- Tests for inclusion/exclusion, pagination stability, invalid bbox, sort injection, role scope.

**Out of scope:** duplicate suggestions (B09), clustering, full-text search, saved filters, analytics, tile server.

**Design constraints:**

- Spatial predicates in PostGIS, not in-memory filtering.
- No unbounded result set. No unrestricted sort/column interpolation.
- Avoid N+1 queries; use projections/DTOs.
- Do not expose internal fields or reporter personal data in map markers.

**Deliverables:**

- Query/repository/controller changes, indexes migration, map endpoint, DTOs, tests, OpenAPI, `CHANGELOG.md`.

**Acceptance criteria:**

- Each filter narrows results and composes with pagination.
- Bbox includes a point inside and excludes a point outside; invalid bbox is rejected.
- Page/size limits hold; ordering is stable across pages.
- Citizen/technician scopes cannot leak incidents.
- Map marker DTO contains only id, coordinates, status, category, priority, updatedAt.
- Query plans show the spatial index is used for a representative bbox (report evidence).

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: seed data, call filters and bbox, compare with known points.

---

### B07 — External context and graceful degradation

**Priority:** P0. **RF:** RF23–24, RF26. **Depends on:** B02. **Timebox:** 4 h.

**Read first:** `AGENTS.md` section 8; ADR-0002, ADR-0009, ADR-0012 (Proposed); approved D5.

**Goal:** enrich incidents with normalized external observations without ever blocking report creation.

**In scope:**

- `external_observations` migration; incident context columns.
- Provider interfaces and a Nominatim adapter for reverse geocoding and address search.
- `GET /api/v1/geocoding/search?q=`, `GET /api/v1/geocoding/reverse?lat=&lon=`, `GET /api/v1/incidents/{id}/context`, `POST /api/v1/incidents/{id}/context/refresh` (operator, `202`).
- Async enrichment after `IncidentCreated` with `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` on a bounded executor (no Modulith, section 3.2); timeouts; one retry on transient 5xx; no retry on 429.
- Recovery for lost events: an incident whose context stays `PENDING` is visible as such and can be refreshed by an operator. Document the limitation (no persisted event registry).
- In-process TTL cache behind an interface; no Redis in P0.
- Context status transitions: `PENDING`, `AVAILABLE`, `PARTIAL`, `UNAVAILABLE`, `STALE`.
- Observations preserve source, source URL, observedAt, ingestedAt, status, quality, payload, error.
- Tests with fake providers: success, timeout, provider down, stale, invalid payload.
- Incident creation test with provider down: `201`, context `UNAVAILABLE`/`PENDING`, no rollback.

**Out of scope:** district/asset geometry (B08), Redis, scheduled retries, historical correlation, real provider calls in tests, AI.

**Design constraints:**

- The incident transaction commits before enrichment. Never hold a DB transaction open during an HTTP call.
- The external call is not on the request path of incident creation.
- Do not call Nominatim/Overpass from web/mobile.
- User-Agent identifies UrbanPulse and a contact email; respect usage policy.
- Do not mark `AVAILABLE` when the observation is missing, stale, or partial.
- Do not log provider payloads containing personal data.

**Deliverables:**

- `externaldata` module, migration, context columns/endpoints, adapter, cache, tests, OpenAPI, `.env.example`, `CHANGELOG.md`.
- ADR-0009/0012 notes in the report; do not change ADR status without approval.

**Acceptance criteria:**

- Provider down: incident creation still returns `201`; context is not falsely `AVAILABLE`; an observation/error is recorded.
- Provider success: address/context is persisted with source/time; context becomes `AVAILABLE`/`PARTIAL` correctly.
- Stale observation is marked `STALE`, not current.
- Refresh is operator-only and returns `202`; citizens cannot trigger external calls.
- No external HTTP in unit tests; integration tests use fakes/fixtures.
- Timeouts and bounded retry are tested.

**Verification:**

```powershell
cd urbanpulse
./mvnw.cmd spotless:apply
./mvnw.cmd verify
```

Manual: create an incident with the provider URL invalid; confirm creation and context status. Restore the provider and refresh.

**Stop/report:** if the external provider is unreachable from the environment, use a fake for tests and document that no live integration was verified. Do not claim live success.

---

### B08 — District, neighborhood, and urban assets (P1)

**Priority:** P1. **RF:** RF21–22. **Depends on:** B07. **Timebox:** 3 h.

**Read first:** ADR-0009, ADR-0003, AGENTS section 8; B07 observation model.

**Goal:** resolve district/neighborhood and nearby urban assets with provenance, alternatives, and a documented fallback.

**In scope:**

- Timeboxed OpenData Málaga catalogue audit (2 h maximum). Record what actually exists: CKAN/API endpoints, district polygons, asset inventories, formats.
- `geospatial` module and P1 tables for districts/assets if data is available; otherwise implement the adapter contract and degraded path without fabricating data.
- Point-in-polygon with PostGIS when polygons exist; centroid plus minimum distance fallback when they do not.
- Nearby assets within a bounded radius, returning alternatives and confidence/distance.
- Persist source, observed/ingested time, quality, geometry, and confidence.
- Context UI data: source label, observation time, `STALE`/`UNAVAILABLE`.

**Out of scope:** inventing a municipal catalogue, scraping non-public data, storing fabricated polygons, routing to an asset, asset maintenance workflows.

**Design constraints:**

- If the catalogue lacks geometry/assets, say so in the report and demo. Do not fill the gap with synthetic production data.
- Do not turn an uncertain asset inference into a confirmed association; return alternatives.
- Normalize all provider data before it reaches the incident domain.
- `incidents` references districts/assets as D0 and the class diagram allow; `geospatial` never writes incident tables directly.

**Deliverables:** module/adapters/migrations if data exists, context DTOs, tests with fixtures, documentation of the audit/limitation, `CHANGELOG.md`.

**Acceptance criteria:** official source used when available; fallback documented; source/time/quality persisted; alternatives returned when ambiguous; missing data clearly unavailable; tests do not call real providers.

**Stop/report:** if both sources lack the needed data, stop after the audit and report the limitation. Do not spend the delivery week building a fictitious dataset.

---

### B09 — Duplicate suggestions (P1)

**Priority:** P1. **RF:** RF16. **Depends on:** B06. **Timebox:** 2 h.

**Goal:** suggest possible duplicates without blocking creation.

**In scope:** `GET /api/v1/incidents/{id}/duplicates` or a pre-create suggestion endpoint; radius/category/text similarity; top N candidates with distance and reason; tests; OpenAPI.

**Out of scope:** automatic blocking, automatic merge, rejection by algorithm, UI (optional W02 enhancement).

**Design constraints:** suggestions are advisory; the report path stays unchanged; no automatic state transition; permissions respect incident visibility.

**Acceptance criteria:** candidate list is bounded and explainable; creation succeeds regardless; no duplicate auto-link; cross-user data is not leaked.

---

### B10 — In-app notifications (P1)

**Priority:** P1. **RF:** RF14. **Depends on:** B04–B05. **Timebox:** 2 h.

**Goal:** create in-app notifications for assignment/status/comment events.

**In scope:** `notifications` table/module; event listeners after commit; `GET /notifications`, `POST /notifications/{id}/read`; unread count; tests.

**Out of scope:** email, SMS, push, notification preferences, Redis, delivery retries.

**Design constraints:** event listeners are eventually consistent; a notification failure must not roll back the incident; no sensitive internal note content in citizen notifications; no personal data in logs.

**Acceptance criteria:** assignment/status change creates a notification for the right user; mark-read is authorized; duplicate event delivery does not create uncontrolled duplicates (idempotency key or unique constraint).

---

### B11 — Statistics, zonal analysis, and reports (P2)

**Priority:** P2. **RF:** RF15, 17, 19. **Depends on:** B06. **Timebox:** 4 h.

**Goal:** provide role-scoped aggregates and export, only if P0/P1 are complete.

**In scope:** counts by status/category/priority/time/district; analyst/admin access; CSV export with filters; privacy-preserving aggregates; tests.

**Out of scope:** dashboards beyond basic counts, BI tooling, ML, historical correlation, personal data export.

**Design constraints:** analysts see aggregates only; no evidence or personal data; export limits and audit; no cross-module joins (use public APIs/read models with an accepted decision).

**Acceptance criteria:** counts match seeded data; citizen/technician cannot access; CSV respects filters and limits; no personal data leaks.

---

### B12 — AI assistance and historical correlation (P2)

**Priority:** P2. **RF:** RF20, 25. **Depends on:** B02, B07. **Timebox:** 4 h.

**Goal:** leave a real extension point; do not fake AI.

**In scope (only if explicitly promoted):** provider adapter behind an interface; classification/priority/summary suggestion with model version, inputs, output, and confidence; human acceptance; audit; no automatic action; tests with fake provider.

**Out of scope:** inventing confidence, calling a paid provider without credentials, auto-validating/rejecting, RAG without documents, historical correlation without data.

**Design constraints:** text from citizens/external sources is untrusted data, not instructions; no sensitive action by model output alone; record provenance; no keys in repo/client.

**Acceptance criteria:** suggestions are labeled as suggestions; confidence is only shown when provided by a real model; human decision is separate and audited; failure degrades gracefully; no fabricated data.

**Stop/report:** if no real provider/data is available, do not implement a fake model. Report that RF20/RF25 remain out of scope.

---

### C01 — Monorepo, shared packages, and generated API client

**Priority:** P0. **RF:** client foundation. **Depends on:** B03. **Timebox:** 3 h.

**Read first:** ADR-0006, ADR-0007, ADR-0008; `docs/api/openapi.yaml`; approved D1.

**Goal:** create the TypeScript workspace and a single typed API client shared by web and mobile.

**In scope:**

- Root `package.json`, `pnpm-workspace.yaml`, `tsconfig.base.json`, `.npmrc` (`node-linker=hoisted` if required by Expo), scripts.
- `packages/domain`: runtime enums/labels/formatters and Zod schemas for auth/create-incident forms; no React.
- `packages/api-client`: `openapi-typescript` generation from `docs/api/openapi.yaml`, typed wrapper using `openapi-fetch` or a small fetch wrapper, base URL, bearer provider, single-flight refresh, `ProblemDetail` mapping, optional `credentials: include`.
- `packages/ui`: design tokens (CSS variables) and tiny platform-agnostic pieces; no web-only dependencies.
- `pnpm api:generate` and `pnpm api:check` (regenerate and fail on drift); OpenAPI lint script.
- Unit tests for the API client refresh/error behavior.

**Out of scope:** app screens, backend changes, deployment, push notifications, component library.

**Design constraints:**

- Contract-first: do not hand-write API DTOs that duplicate the OpenAPI schema.
- No `any` in public client APIs.
- No tokens in localStorage; the client receives a token provider.
- Web and mobile must use the same client; platform differences via options.
- Pin the package manager and lockfile.

**Deliverables:** workspace files, shared packages, scripts, generated types, tests, `.gitignore` updates, `CHANGELOG.md`.

**Acceptance criteria:** `pnpm install`, `pnpm api:check`, typecheck, lint, and client tests pass; generated types match the contract; refresh is single-flight; errors map to a typed problem; no secrets.

**Verification:**

```powershell
pnpm install
pnpm api:generate
pnpm api:check
pnpm -r typecheck
pnpm -r test
```

**Stop/report:** if `openapi-typescript`/`openapi-fetch` is incompatible with the current Node/TS versions, pin compatible versions and report; do not switch to a different contract strategy without approval.

---

### W01 — Web shell and authentication

**Priority:** P0. **RF:** RF01–02. **Depends on:** C01, B01. **Timebox:** 3 h.

**Goal:** a working web shell with login, registration, session refresh, logout, and protected routes.

**In scope:** Vite React TS app; React Router; TanStack Query; CSS Modules; auth context/store; login/register/logout; boot refresh; protected route; app layout; error boundary; placeholder routes for incidents/operator; Vitest/RTL tests; Vite proxy `/api` to backend.

**Out of scope:** incident screens (W02), operator actions (W04), admin, map, design system.

**Design constraints:** access token in memory; refresh via HttpOnly cookie with `credentials: include`; one refresh attempt on 401; redirect to login; no tokens in URLs; accessible forms; no secrets in `VITE_*`.

**Acceptance criteria:** register/login/logout work; reload keeps the session via refresh; invalid login shows a safe error; protected routes redirect; expired access triggers one refresh; tests pass; no token storage in localStorage.

**Verification:** `pnpm --filter ./apps/web dev`, `pnpm --filter ./apps/web typecheck`, `pnpm --filter ./apps/web test`, `pnpm --filter ./apps/web build`; manual browser flow.

---

### W02 — Web citizen flow

**Priority:** P0. **RF:** RF03–06. **Depends on:** W01, B03, B07. **Timebox:** 4 h.

**Goal:** citizens can report, attach evidence, and track their incidents on the web.

**In scope:** create form (title, description, optional category, map/location, address, accuracy); MapLibre picker with OSM attribution; backend geocoding search/reverse; evidence upload with progress/retry; my incidents list; detail with public timeline, evidence, public comments; edit while `REPORTED`; loading/empty/error/success states; tests.

**Out of scope:** operator actions, internal notes, duplicate UI (P1), offline drafts, mobile.

**Design constraints:** no direct Nominatim/Overpass calls; server validation is authoritative; prevent duplicate submissions; client file checks are hints only; accessible labels/focus; mobile responsive.

**Acceptance criteria:** create with/without category and evidence; location accuracy shown; invalid file/size handled; list/detail only own incidents; timeline hides internal notes; retry works; tests cover form and error states.

**Verification:** `pnpm --filter ./apps/web typecheck`, `test`, `build`; Playwright later; manual create/upload/track.

---

### W03 — Web operator queue and map

**Priority:** P0. **RF:** RF07–08. **Depends on:** W02, B06. **Timebox:** 3 h.

**Goal:** operators can find incidents through URL-driven filters and a map.

**In scope:** queue page with filters (status, category, priority, department, assignee, date, text), pagination, URL search params; list/map split view; bbox query on map move; role-scoped data; loading/empty/error; tests.

**Out of scope:** detail/actions (W04), admin, analytics, duplicate UI.

**Design constraints:** filters are shareable/bookmarkable; no unbounded requests; debounce map/text queries; map attribution; accessible filter labels; no client-only permission assumption.

**Acceptance criteria:** filters compose and survive reload; pagination stable; map bbox matches list; empty/error states; operator only; tests for filter serialization.

**Verification:** typecheck/test/build; manual filter/map flow.

---

### W04 — Web operator detail and actions

**Priority:** P0. **RF:** RF09–13, 18, 24. **Depends on:** W03, B04–B05. **Timebox:** 4 h.

**Goal:** operators and technicians can execute the lifecycle from the incident detail with audit and context.

**In scope:** detail view; `allowedActions`-driven buttons; validate/reject/request information; priority with justification; assign/reassign/unassign; start/resolve/close/reopen; public/internal comments; status/history timeline; evidence; context panel (source/time/quality/status); conflict handling (`409`); tests.

**Out of scope:** admin, notifications, AI, duplicate UI, bulk actions.

**Design constraints:** backend enforces permissions; UI hides unavailable actions but is not the control; require reasons; disable during submit; invalidate queries after action; do not expose internal notes to citizen views; handle stale version with a user-visible reload.

**Acceptance criteria:** operator can complete validate → priority → assign; technician can start/resolve; wrong role gets no action and `403` if forced; invalid transition shows a safe conflict; timeline/audit reflect actor/time/reason; internal notes hidden from citizen; tests cover action gating and conflicts.

**Verification:** typecheck/test/build; manual full lifecycle with two roles.

---

### W05 — Web admin and analyst (P1)

**Priority:** P1. **RF:** RF14–15, 17, 19. **Depends on:** W04. **Timebox:** 3 h.

**Goal:** basic administration and role-scoped analytics, only if P0/P1 are green.

**In scope:** admin categories/departments/users; analyst basic counts and CSV export if B11 exists; guards; empty/error states; tests.

**Out of scope:** advanced dashboards, charts beyond basic counts, user invitations, audit UI beyond incident history, personal data for analysts.

**Acceptance criteria:** admin-only routes enforced; analyst sees aggregates only; export respects filters/limits; no evidence/personal data leak.

---

### M01 — Mobile shell and authentication

**Priority:** P0. **RF:** RF01–02. **Depends on:** C01, B01. **Timebox:** 3 h.

**Goal:** an Expo app with login, registration, refresh, logout, and secure token storage.

**In scope:** Expo + TypeScript + Expo Router (built on React Navigation, which ADR-0007 names; state this in the report); tabs (Report, My reports, Profile); SecureStore for refresh; access token in memory; API client; env `EXPO_PUBLIC_API_URL`; loading/error states; tests.

**Out of scope:** report form (M02), map, push, offline drafts.

**Design constraints:** no `localhost`; document `10.0.2.2` for Android emulator and LAN IP for device; no tokens in AsyncStorage; permissions asked contextually; accessible controls.

**Acceptance criteria:** login/register/logout; app restart restores session via SecureStore; expired access refreshes; wrong credentials safe error; tests pass; manual device/emulator check.

**Verification:** `pnpm --filter ./apps/mobile start`, typecheck, test; manual Expo Go flow.

---

### M02 — Mobile citizen flow

**Priority:** P0. **RF:** RF03–06, 08. **Depends on:** M01, B03, B07. **Timebox:** 4 h.

**Goal:** citizens can report with GPS/camera and track their incidents on mobile.

**In scope:** report form; location permission and current position; map/coordinate confirmation; address reverse; optional category; camera/gallery evidence with permission; upload progress/retry; my reports list; detail/timeline/evidence; pull-to-refresh; error/empty states; tests/manual checklist.

**Out of scope:** operator actions, push, offline drafts, embedded MapLibre (P1), video.

**Design constraints:**

- P0 baseline: coordinate picker plus a static OSM preview and an "open in maps" action, unless an Expo dev build with MapLibre works within a 2-hour spike. If MapLibre blocks, keep the fallback and report the limitation. Do not silently change ADR-0005.
- Request location/camera only when needed; explain denial and allow manual correction.
- Show accuracy limitations; do not pretend GPS is exact.
- Prevent duplicate submits; upload retry; no secrets.
- Use the shared API client and domain schemas.

**Acceptance criteria:** login; grant/deny permissions; create with GPS and camera; optional category; evidence upload; list/detail; timeline hides internal notes; retry on failure; manual check on at least one platform; tests for form/permission-denied paths.

**Verification:** typecheck/test; manual device checklist; screenshots optional.

---

### M03 — Mobile map, push, and polish (P1)

**Priority:** P1. **RF:** RF08, 14. **Depends on:** M02. **Timebox:** 3 h.

**Goal:** embedded MapLibre and push only if the dev build and backend notifications are ready.

**In scope:** MapLibre dev build; embedded map with OSM tiles/attribution; push token registration and notification handling if B10 exists; UX polish; tests.

**Out of scope:** background sync, offline maps, EAS production release, SMS/email.

**Acceptance criteria:** map renders and marker matches the report; push permission handled; no secrets; fallback remains documented if blocked.

---

### Q01 — End-to-end, accessibility, and quality gate

**Priority:** P0. **RF:** critical cases. **Depends on:** W02, W04, M02. **Timebox:** 3 h.

**Goal:** prove the critical flows and prevent regressions.

**In scope:**

- Playwright web E2E: register/login citizen, create incident with evidence, operator validates/prioritizes/assigns, technician starts/resolves, citizen sees result; unauthorized access checks.
- `axe` accessibility smoke on login, create, queue, detail.
- Mobile manual checklist: auth, permissions, report, evidence, list/detail, retry.
- Test data setup without real external providers; deterministic clock where needed.
- With D11's default, the citizen part of the E2E flow is created through the API (test setup), and the mobile checklist covers the citizen UI.
- Fix flaky tests; no lowering coverage; capture screenshots/traces on failure.

**Out of scope:** load/performance testing, cross-browser matrix beyond Chromium, mobile E2E automation (P2).

**Acceptance criteria:** critical flow passes locally; no real external calls; accessibility smoke has no serious violations; mobile checklist is signed off; failures produce useful artifacts.

---

### F02 — Backend hardening, seed, and final gate

**Priority:** P0. **RF:** backend gate. **Depends on:** B07. **Timebox:** 2 h.

**Goal:** make the backend demoable and consistent.

**In scope:**

- `dev`-profile seed for users (citizen/operator/technician/admin), categories, departments, and sample incidents at different statuses. No production seeding.
- Final OpenAPI review; error-code consistency; pagination; no entity leaks.
- Performance review: indexes, N+1, stable ordering; bounded queries.
- Logging review: no secrets/tokens/personal data; correlation IDs.
- Run full backend verify, coverage, SpotBugs, Spotless; fix real issues.
- Update backend docs/CHANGELOG; note unimplemented RFs.

**Out of scope:** new features, admin CRUD UI, AI, Docker, P1.

**Acceptance criteria:** full `verify` green; coverage ≥ 0.80; demo users/data work; no secrets; all P0 backend endpoints documented; known limitations listed.

---

### D01 — Docker, nginx, and compose (P1)

**Priority:** P1. **RF:** deployment. **Depends on:** F02, C01, W01. **Timebox:** 3 h.

**Goal:** package the system reproducibly, only if P0 is green or the rubric requires it.

**In scope:** `urbanpulse/Dockerfile` multi-stage, non-root; `apps/web/Dockerfile`; `infra/nginx/default.conf` serving the SPA and proxying `/api/`; root `compose.yaml` with db/backend/web; internal network; only nginx published; healthchecks; evidence volume; env placeholders; docs.

**Out of scope:** TLS, Kubernetes, Terraform, Redis (unless accepted), mobile in Compose, production secrets.

**Design constraints:** same pinned images across environments; no `latest`; no secrets in images; DB not published in prod compose; upload size limit in nginx; backend healthcheck.

**Acceptance criteria:** `docker compose up --build` starts the stack; health UP; SPA login/create flow works; restart preserves DB and evidence; only the web port is public; no secrets committed.

---

### D02 — CI, documentation, and demo

**Priority:** P0. **RF:** delivery gate. **Depends on:** all P0. **Timebox:** 3 h.

**Goal:** leave a reproducible, honest delivery.

**In scope:**

- CI: keep backend jobs; add web/mobile install, lint, typecheck, test, build (no EAS credentials); add OpenAPI lint; no required-check renames without human approval; cache pnpm.
- Confirm that P00 removed `boilerplate` from the workflow triggers; if not, remove it here.
- README: status, architecture, quick start for backend/web/mobile, env vars, test commands, dev demo users, links to guides.
- `docs/development.md` and `docs/deployment.md` (if D01) updated/created.
- `CHANGELOG.md` complete.
- ADR statuses: change only with human approval; record new decisions (token transport, repo layout) if needed.
- Fix or explicitly mark broken links (`docs/architecture/workspace.dsl`, `docs/infra/docker/DOCKER.md`, `docs/api.md`, issue-template paths/job names) and any Modulith reference left after P00.
- Demo script and known limitations.
- Final verification matrix with actual results.

**Out of scope:** new features, large refactors, C4 authoring if it cannot be completed honestly.

**Acceptance criteria:** CI green on the PR; all documented commands work; README matches reality; no unimplemented RF claimed as done; known limitations listed; demo script rehearsed.

---

## 8. Cross-cutting review checklist

Use this before reporting a task as done.

### 8.1 Security and privacy

- Every endpoint has an authentication/authorization test.
- Ownership/assignment checks are in the service, not only the controller/UI.
- No IDOR on incidents, evidence, comments, history, notifications, or context.
- Internal notes and personal data never reach citizen/analyst responses.
- No secrets, tokens, hashes, storage keys, or stack traces in responses/logs.
- Evidence validates magic bytes, size, and authorization; keys are server-generated.
- Refresh rotation/revocation works; cookies have the right flags; no localStorage tokens.
- No wildcard CORS; cookie refresh/logout validates origin.
- External input is treated as data, not instructions.

### 8.2 Data and consistency

- New schema change has a new migration; no applied migration was edited.
- Status/history/audit/assignment writes are atomic.
- Invalid transition leaves no partial state.
- Optimistic locking tested.
- Cross-module relationships only as allowed by D0 and the class diagram; module dependencies listed in the report.
- Location latitude/longitude order and range tested.
- UTC instants; observed vs ingested time preserved.
- Unknown/unavailable is not zero or current.

### 8.3 API and contract

- OpenAPI updated for every endpoint/field/error change.
- Response DTOs do not expose entities.
- Pagination default/max/stable order.
- ProblemDetail codes stable and documented.
- Status codes match the convention.
- Generated client is up to date (`pnpm api:check`).

### 8.4 UI and mobile

- Loading, empty, error, retry, success states.
- No duplicate submit; buttons disabled during requests.
- Accessible labels, focus, contrast, keyboard, touch targets.
- Map attribution; no direct provider calls.
- Mobile permissions explained and denial handled.
- Mobile API URL not `localhost`.
- Client hides unavailable actions but backend enforces them.

### 8.5 Quality and delivery

- `spotless:apply`/check; no unrelated formatting.
- `verify`/web/mobile checks actually run.
- Coverage not lowered.
- No dead/commented-out code.
- `CHANGELOG.md` updated.
- No secrets/generated files/unrelated changes.
- Report is honest about what was not run or not implemented.

---

## 9. Appendices

### 9.1 Endpoint inventory

| Method | Path | Access | Task |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | B01 |
| POST | `/api/v1/auth/login` | public | B01 |
| POST | `/api/v1/auth/refresh` | cookie/mobile token | B01 |
| POST | `/api/v1/auth/logout` | authenticated/cookie | B01 |
| GET | `/api/v1/users/me` | authenticated | B01 |
| POST | `/api/v1/incidents` | citizen/operator/admin | B02 |
| GET | `/api/v1/incidents` | role-scoped | B02/B06 |
| GET | `/api/v1/incidents/{id}` | role-scoped | B02 |
| PATCH | `/api/v1/incidents/{id}` | owner, `REPORTED` | B02 |
| GET | `/api/v1/categories` | authenticated | B02 |
| GET | `/api/v1/departments` | authenticated | B05 |
| POST | `/api/v1/incidents/{id}/attachments` | role-scoped | B03 |
| GET | `/api/v1/incidents/{id}/attachments` | role-scoped | B03 |
| GET | `/api/v1/incidents/{id}/attachments/{attachmentId}` | role-scoped | B03 |
| POST | `/api/v1/incidents/{id}/validate` | operator/admin | B04 |
| POST | `/api/v1/incidents/{id}/reject` | operator/admin | B04 |
| POST | `/api/v1/incidents/{id}/request-information` | operator/admin | B04/B05 |
| POST | `/api/v1/incidents/{id}/priority` | operator/admin | B04 |
| POST | `/api/v1/incidents/{id}/start` | assigned technician | B04 |
| POST | `/api/v1/incidents/{id}/resolve` | assigned technician | B04 |
| POST | `/api/v1/incidents/{id}/close` | operator/admin | B04 |
| POST | `/api/v1/incidents/{id}/reopen` | owner/operator/admin | B04 |
| GET | `/api/v1/incidents/{id}/history` | role-scoped | B04 |
| POST | `/api/v1/incidents/{id}/assign` | operator/admin | B05 |
| POST | `/api/v1/incidents/{id}/unassign` | operator/admin | B05 |
| POST | `/api/v1/incidents/{id}/comments` | role-scoped | B05 |
| GET | `/api/v1/incidents/{id}/comments` | role-scoped | B05 |
| GET | `/api/v1/incidents/{id}/context` | role-scoped | B07 |
| POST | `/api/v1/incidents/{id}/context/refresh` | operator/admin | B07 |
| GET | `/api/v1/geocoding/search` | authenticated | B07 |
| GET | `/api/v1/geocoding/reverse` | authenticated | B07 |
| GET | `/api/v1/map/incidents` | role-scoped | B06 |
| GET | `/api/v1/incidents/{id}/duplicates` | role-scoped | B09 |
| GET | `/api/v1/notifications` | authenticated | B10 |
| POST | `/api/v1/notifications/{id}/read` | owner | B10 |
| GET | `/api/v1/statistics/incidents` | operator/admin/analyst | B11 |
| GET | `/api/v1/incidents/export.csv` | operator/admin/analyst | B11 |
| GET/POST/PATCH | `/api/v1/admin/categories` | admin | W05/P1 |
| GET/POST/PATCH | `/api/v1/admin/departments` | admin | W05/P1 |
| GET/PATCH | `/api/v1/admin/users` | admin | W05/P1 |

### 9.2 Migration sequence

| Migration | Owner | Content |
|---|---|---|
| `V1__enable_postgis.sql` | F01 | `CREATE EXTENSION IF NOT EXISTS postgis` |
| `V2__identity.sql` | B01 | `users`, `refresh_tokens`, indexes |
| `V3__incidents.sql` | B02 | `categories`, `incidents`, generated location, checks, indexes, category seed |
| `V4__attachments.sql` | B03 | `attachments`, indexes |
| `V5__audit_and_lifecycle.sql` | B04 | `audit_events`, `status_changes`, indexes |
| `V6__assignments_and_comments.sql` | B05 | `departments`, `assignments`, `comments`, indexes, department seed |
| `V7__incident_query_indexes.sql` | B06 | Query indexes/GIST if not already created |
| `V8__external_context.sql` | B07 | `external_observations`, context columns |
| `V9__geospatial.sql` | B08 | Districts/assets if the audit finds data |
| `V10__notifications.sql` | B10 | `notifications` |

Migrations are illustrative until the task executes. Never edit an applied migration; add a new one. The numbers are reserved per task so that parallel tracks do not collide (section 6.1); if a task does not need its migration, leave the number unused rather than shifting the others.

### 9.3 Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Field/body validation failed |
| `AUTH_INVALID_CREDENTIALS` | 401 | Login failed |
| `AUTH_TOKEN_EXPIRED` | 401 | Access/refresh token expired |
| `AUTH_UNAUTHORIZED` | 401 | Missing/invalid authentication |
| `AUTH_FORBIDDEN` | 403 | Role/ownership/assignment denied |
| `RESOURCE_NOT_FOUND` | 404 | Resource missing or hidden by scope |
| `EMAIL_ALREADY_REGISTERED` | 409 | Duplicate registration |
| `INCIDENT_INVALID_TRANSITION` | 409 | Transition not allowed from current state |
| `INCIDENT_CONFLICT` | 409 | Optimistic-lock/stale version |
| `ASSIGNMENT_INVALID` | 409 | Invalid technician/department/state |
| `ATTACHMENT_TOO_LARGE` | 413 | File exceeds limit |
| `ATTACHMENT_TYPE_NOT_ALLOWED` | 400 | Magic bytes/type not allowed |
| `EXTERNAL_SOURCE_UNAVAILABLE` | 503 | Provider failure; report may still be valid |
| `RATE_LIMITED` | 429 | Login/endpoint limit |

The API must not expose provider error bodies or stack traces.

### 9.4 Critical test cases

| Case | Task | Expected |
|---|---|---|
| Create report while external source is down | B07 | `201`; context `PENDING`/`UNAVAILABLE`; no rollback |
| Unauthorized read/modify incident or evidence | B02/B03/B04 | `403`/`404`; no data leakage |
| Invalid transition | B04 | `409`; no status/history/audit partial change |
| Decision actor/time/reason | B04 | Audit and history rows complete |
| Duplicate suggestion does not block | B09 | Creation succeeds; candidates advisory |
| Absent vs stale context | B07 | Distinct states; no false current data |
| Invalid file size/type/permission | B03 | Rejected; no orphan record/file |
| Internal note exposure | B05/W04 | Citizen never sees internal notes |
| Cross-user list/map/history | B06/W03/W04 | Role scope enforced |
| Refresh token reuse | B01 | Rejected/revoked; session not silently extended |

### 9.5 Seed data for demo

Use a `dev`-only seeder. Suggested accounts (passwords are local demo placeholders, never production):

| Role | Email | Password placeholder |
|---|---|---|
| Citizen | `citizen@urbanpulse.test` | `ChangeMe123!` |
| Operator | `operator@urbanpulse.test` | `ChangeMe123!` |
| Technician | `technician@urbanpulse.test` | `ChangeMe123!` |
| Admin | `admin@urbanpulse.test` | `ChangeMe123!` |

Seed categories (ALUMBRADO, RESIDUOS, ACERAS, TRAFICO, AGUA, OTROS), departments, and incidents in `REPORTED`, `ASSIGNED`, `RESOLVED`, and `REJECTED` states. Do not seed production. Do not commit real credentials.

### 9.6 Session prompt example

```text
You are implementing UrbanPulse. Read AGENTS.md, sections 0–5 of docs/guias/guia-ia.md, the task card <ID>, `git log --oneline -15`, and the current code/tests. Execute only <ID>. Do not start any other task.

Before coding: restate the goal, in-scope/out-of-scope, files you expect to touch, and any blocking decision. Wait if a decision from section 5.1 is unresolved.

Implement the task with tests, OpenAPI, migration, env example, and CHANGELOG updates where applicable. Run the verification commands in the card and report actual results. Do not commit or push. Stop after the report.
```

---

## 10. Final rule

A working, tested, honest vertical slice is the deliverable. Do not trade correctness, permissions, auditability, or degraded-mode behavior for breadth. If the week runs out, report exactly what is not implemented and why; do not fake it.
