# UrbanPulse — Human Delivery Guide

> **Delivery window:** one week, starting 2026-10-20.
> **Audience:** the person coordinating and reviewing the implementation.
> **Companion document:** `docs/guias/guia-ia.md` (task cards and technical rules for the AI agent).
> **Status:** this is an operating plan, not evidence that a feature exists.
> **Revision:** 2026-10-20 r2 — reconciled with the repository at `778fbc4` (Spring Modulith removed in `6a2c356`, PR-title check, CI smoke test, issue-template actor split).

You are the coordinator, reviewer, and demo owner. The AI writes code; you decide what is in the scope of each task, verify claims, and protect the delivery from scope creep. Decisions that change the domain, permissions, public contract or architecture are **team** decisions (`AGENTS.md` section 1): you bring them to the team, record them, and only then hand them to the AI.

---

## 0. How to use this guide

1. Complete **Day 0**, including committing both guides to `docs/guias/` (the AI reads them from there), and get the decisions in section 2 approved by the team.
2. Give the AI the onboarding prompt in section 2.4. It must produce a read-only gap report first.
3. For each task, use the prompt in section 4. The AI must execute **one task only**.
4. Review the diff and run the verification yourself. Do not trust a summary.
5. Commit or ask the AI to commit only after the task passes.
6. Do not start a dependent task until the previous gate is green.
7. At the end, run the demo in section 6 and state the known limitations honestly.

The AI guide defines the technical details. This guide defines what you do and what you say to the AI.

**Success is not "all 26 RFs attempted". Success is a working, tested, honest vertical slice** covering the P0 scope in `guia-ia.md` section 2.

---

## 1. Day 0 — Setup and baseline

### 1.1 Install and verify tools

Install:

- Java 25 JDK; `JAVA_HOME` points to it.
- Docker Desktop or Docker Engine with Compose v2.
- Git.
- Node.js 22 LTS and Corepack/pnpm.
- An editor (IntelliJ IDEA recommended for the backend).
- Expo Go on a phone, and/or Android Studio/Xcode if you will use a development build.

Verify:

```powershell
java --version
docker --version
docker compose version
node --version
pnpm --version
git --version
```

If `pnpm` is not available:

```powershell
corepack enable
pnpm --version
```

Corepack downloads the pnpm version pinned in the root `package.json` (`"packageManager": "pnpm@<exact version>"`), which C01 adds. Until then, any recent pnpm is fine for checking the tool. Do not use `@latest` in project files, and do not mix npm, yarn, and pnpm lockfiles.

### 1.2 Update the baseline

Current repository state: the working branch is `boilerplate`, and `main` is behind it. Do not develop directly on `boilerplate`.

1. Fetch and inspect:

```powershell
git fetch origin
git switch main
git pull origin main
git log --oneline -5
```

2. If `main` is behind `boilerplate`, open a pull request from `boilerplate` to `main`:

```powershell
gh pr create --base main --head boilerplate --title "chore: promote project baseline" --body "Promote the reviewed backend skeleton, CI, ADRs and documentation to main."
```

If `gh` is unavailable, use the GitHub web UI.

**Before merging**, add a commit to that PR that removes `boilerplate` from the `branches` lists of `.github/workflows/ci.yml` and `.github/workflows/pr-title.yml`; both files say `TEMPORARY: remove boilerplate before merging into main`. Wait for `lint`, `test`, `build` and `pr-title` to pass, then squash merge.

2b. Open a second, documentation-only PR (`docs: add delivery guides and decision records`) with:

- `docs/guias/guia-ia.md` and `docs/guias/guia-persona.md`.
- The team's class diagram (D12), for example `docs/architecture/class-diagram.png` plus its source.
- The decision records for D0, D2, D4, D10, D11 as ADRs with status `Proposed` (new files from `.github/adr-template.md`, or amendments of ADR-0004/ADR-0010). The AI can draft them with prompt 8.10; the team accepts them.
- Removal of the stale Spring Modulith / `ModularityTest` references in `README.md`, `docs/ci.md`, `docs/ci-setup.md` and ADR-0015.

Merge it once the team has accepted the decisions. Every later AI session reads these files, so stale text here costs time in every task.

3. Update local `main` and create the first task branch:

```powershell
git switch main
git pull origin main
git switch -c feature/f01-delivery-spine
```

4. Do not merge unrelated work into this branch. Later tasks branch from `main` after the previous PR is squash-merged.

### 1.3 Local environment

```powershell
Copy-Item urbanpulse\.env.example urbanpulse\.env
```

Edit `urbanpulse/.env` and set a local database password. Never commit `.env`, never paste its contents into the AI chat, and never put real secrets in `.env.example`.

Add the new variables from `guia-ia.md` section 4.10 as tasks require them. Use placeholders in `.env.example`.

Spring Boot does not read `.env` by itself; Docker Compose does. When running from IntelliJ, load `urbanpulse/.env` in the run configuration of `UrbanpulseApplication`, and add `SPRING_PROFILES_ACTIVE=dev` once F01 creates profiles. If the app fails at startup after B01 with a missing `JWT_SECRET`, this is the place to look.

### 1.4 Baseline verification

Docker must be running.

```powershell
cd urbanpulse
.\mvnw.cmd verify
```

If the baseline is red, stop and fix it before F01. Record the exact failure and whether it is environmental (Docker, Java, network) or code-related. Do not let the AI "fix" the baseline by disabling tests.

### 1.5 Create tracking

Create a milestone for the delivery and one issue per P0 task. Use the feature template, link the RF, and assign the milestone. At minimum, create issues for F01, B01–B07, C01, W01–W04, M01–M02, Q01, F02, D02.

If you use GitHub CLI, create them manually with the template fields; do not invent a new template. Keep issue titles aligned with task IDs, for example:

```text
[F01] Delivery spine
[B01] Authentication and authorization
[W02] Web citizen flow
```

### 1.6 Branch and PR rule

- One task per branch and PR.
- Branch names: `feature/<task-id>-<slug>` or `fix/<slug>`.
- Conventional Commit messages.
- **PR titles are Conventional Commits too**: `pr-title.yml` fails on anything else, and with squash merge the title becomes the commit on `main`. Put the task ID at the end, e.g. `feat(auth): add JWT authentication and role checks (B01)`. A title like `[B01] Authentication` fails the check; keep that form for issue titles.
- PR body: use `.github/pull_request_template.md` and include `Closes #<issue>` (the check warns without it).
- Squash merge only.
- Update `CHANGELOG.md` in the same PR as the behavior.
- Do not rename CI jobs without updating the branch ruleset.

---

## 2. Decisions you must approve

The AI guide marks these as proposals; `guia-ia.md` section 5.1 is the canonical table (if the two differ, fix this one). Do not let the AI implement a dependent task until the team approves or changes the relevant decision.

Where to record them: D0, D2, D4, D10 and D11 change ADRs or the architecture, so they go in ADRs (new or amended) in the documentation PR of step 2b. A PR description is lost for the next AI session. D5–D9 are delivery choices and can live in the task prompt or PR description.

| ID | Decision | Recommended default | Approve before |
|---|---|---|---|
| D0 | Module rules after removing Spring Modulith | Amend ADR-0010: keep package-by-feature modules and transaction/event rules; drop Modulith annotations/tests; allow the JPA relationships in the class diagram | F01 |
| D1 | Repository layout | Keep `urbanpulse/`; add `apps/*` and `packages/*` | F01/C01 |
| D2 | Status matrix | `guia-ia.md` section 4.7; `REJECTED` and `CLOSED` terminal | B04 |
| D3 | Roles | One primary role per user; `ADMIN` includes operator actions | B01 |
| D4 | Token transport | Access in memory; web refresh HttpOnly cookie; mobile SecureStore | B01 |
| D5 | External cache | In-process TTL; Redis only if ADR-0012 is accepted | B07 |
| D6 | Mobile map | Coordinate picker/static preview P0; MapLibre embedded P1 | M02 |
| D7 | Deployment | Docker/nginx P1; promote if the rubric requires it | D01 |
| D8 | AI/RAG | P2 and out of this delivery; no fake AI | B12 |
| D9 | Evidence policy | JPEG/PNG/WebP/PDF, 10 MB, magic bytes | B03 |
| D10 | Migration tool | Flyway (Boot 4: `spring-boot-starter-flyway` + `flyway-database-postgresql`), new ADR | F01 |
| D11 | Client per actor | Mobile = citizen; web = operator/admin/analyst; technician on web in P0 (exception); W02 → P1 | W01/M01 |
| D12 | Data model source | Class diagram committed to the repo; the guide's table is reconciled with it | B01 |

### 2.1 What each decision means

- **D0:** On 2026-10-20 (`6a2c356`) the team removed Spring Modulith and `ModularityTest` because enforcing boundaries would drop relationships the class diagram already has. ADR-0010 still says the opposite, and the AI reads ADRs as rules: until it is amended, every session will be confused or will reintroduce Modulith. Decide explicitly what stays (package-by-feature modules, atomic work in one transaction, events only after commit) and what goes (annotations, boundary test, ban on cross-module JPA relationships). Note the trade-off: nothing in the build checks boundaries any more, so reviewers must.
- **D1:** Moving the backend to `services/api` costs time and breaks CI paths. Keep it in `urbanpulse/`. Add web/mobile/shared packages around it.
- **D2:** Without an approved matrix, the AI will invent transitions. Decide now. The recommended matrix forbids skipping states and makes `REJECTED`/`CLOSED` terminal. Also answer the six open points listed after the matrix in `guia-ia.md` section 4.7 (department-only assignment, reassigning while in progress, when the assignment ends, reopened → closed, when priority can be set, automatic closing).
- **D3:** One role per user is enough for the demo. Admin can do operator actions. Multi-role support is P1. Also fix the role names: ADR-0004 says `user` where the guides say `CITIZEN`; pick one and use it everywhere.
- **D4:** Do not store refresh tokens in localStorage. Web uses an HttpOnly cookie; mobile uses SecureStore. Access tokens stay in memory. ADR-0004 justified JWT precisely by not depending on browser cookies, so the web cookie is an amendment to ADR-0004, not a detail.
- **D5:** Redis is still a Proposed ADR. Do not add a stateful service in the delivery week. Use a small in-process TTL cache; mark Redis as future work.
- **D6:** MapLibre in Expo requires a development build and can consume hours. P0 mobile needs GPS and a report; embedded MapLibre is P1.
- **D7:** Local dev servers are acceptable if Docker packaging threatens P0. If the rubric explicitly requires containers, promote D01 and cut another P1 item.
- **D8:** Do not fake AI, confidence scores, or RAG citations. If there is no real provider/data, RF20/RF25 stay out.
- **D9:** Images and PDF only. Magic-byte validation is mandatory. Video is P2.
- **D10:** There is no migration tool in the repo and no ADR choosing one, yet every task from F01 on writes migrations. Flyway with plain SQL fits the team (SQL is reviewable, PostGIS DDL is native). In Spring Boot 4 the auto-configuration moved to its own module, so it needs `spring-boot-starter-flyway`; with `flyway-core` alone the migrations are not run at startup (check it in F01: the acceptance criterion "Flyway applies V1" catches it).
- **D11:** The feature issue template already says web = operators, analysts, administrators and mobile = citizens, technicians, but the guides put the citizen flow on web too. Following the template saves W02 (4 h) and matches the team's own planning. Technicians on mobile would add a whole new flow, so the recommendation keeps them on web for P0 and records it as an exception.
- **D12:** `6a2c356` cites "the class diagram" as the reason to drop Modulith, but the diagram is not in the repo, and the guide's data model (section 4.4) was written without it. Commit the diagram so the AI models the team's entities instead of inventing its own.

### 2.2 Decision record template

Copy this into the PR/issue when you approve:

```text
Approved decisions for this task:
- D<x>: <approved/changed> — <short note>
- D<y>: <approved/changed> — <short note>

Scope exceptions:
- <none or explicit list>

Approved by: <team members present / where it was agreed>
Recorded in: <ADR file or AGENTS.md section, for D0/D2/D4/D10/D11>
Date: <YYYY-MM-DD>
```

### 2.3 If you disagree with a recommendation

Change the task prompt and the decision record before the AI starts. Do not let it discover the disagreement halfway through. If the change affects the public contract, update `docs/api/openapi.yaml` and the shared client in the same task.

### 2.4 First AI prompt — read-only onboarding

Give this before any code. It is intentionally read-only.

```text
You are the implementation agent for UrbanPulse. Read AGENTS.md, README.md, all files in docs/adr, docs/ci.md, docs/ci-setup.md, docs/development.md, sections 0–5 of docs/guias/guia-ia.md, .github/workflows, .github/ISSUE_TEMPLATE/01-feature.yml, the output of `git log --oneline -15`, and the current backend code and tests.

Do not modify any file. Produce a gap report with:
1. Claims in the documents that do not match the actual repository (including recent commits that contradict an ADR, such as the removal of Spring Modulith).
2. Conflicts between accepted ADRs, proposed ADRs, README, CI, issue templates, the guides and code.
3. Decisions that must be approved before F01.
4. The recommended P0 task order and its dependency risks.
5. Any baseline problem that would make verification unreliable.

Cite file paths. Stop after the report.
```

Check the report against `guia-ia.md` section 1. If the AI claims a file exists when it does not, correct it and ask it to re-read. Do not accept a report that invents test results.

---

## 3. One-week plan and gates

This plan assumes close to full-time work plus AI assistance. If you have less time, use the cut line in `guia-ia.md` section 2.4 immediately; do not wait until the last day.

Size check: the P0 timeboxes in `guia-ia.md` section 6.2 add up to about 63 hours of AI work, plus about an hour of your review and verification per task (roughly 18 hours). That is more than one person can do in a week, so **split the work between team members** (section 3.0). Confirm the real delivery deadline with the team; the dates below assume Day 0 is today.

### 3.0 Parallel tracks for the team

After B02 is merged, these tracks touch different files and can run at the same time, one person (and one AI session) each:

| Track | Tasks | Notes |
|---|---|---|
| A — clients | B03 → C01 → W01/M01 → M02, W03 → W04 | C01 waits for the B03 contract freeze |
| B — lifecycle | B04 → B05 | Owns the transition matrix (D2) |
| C — search | B06 | Needs B02 only |
| D — context | B07 → F02 | Owns `externaldata` |

Coordination rules: each task uses the migration number reserved in `guia-ia.md` section 9.2; merge in number order when possible (Flyway rejects a lower version after a higher one); everyone rebases before opening a PR because `openapi.yaml` and `CHANGELOG.md` are shared; nobody edits another track's entity or service classes.

| Day | Date | Tasks | Gate / demoable result |
|---|---|---|---|
| 0 | Tue 20 Oct | P00 | Baseline green, `main` updated, guides and decisions committed, branch created |
| 1 | Wed 21 Oct | F01, B01 | App starts with Flyway/PostGIS; auth works; permissions tested |
| 2 | Thu 22 Oct | B02, B03 | Citizen creates incident with/without category and evidence |
| 3 | Fri 23 Oct | B04, B05, B06 | Validate → prioritize → assign → start → resolve; history/audit; filters/map |
| 4 | Sat 24 Oct | B07, C01, W01 | Degraded context works; shared client generated; web login/session |
| 5 | Sun 25 Oct | M01, M02 (W02 only if D11 keeps web citizens) | Mobile report/tracking |
| 6 | Mon 26 Oct | W03, W04, Q01 | Operator console + critical E2E + accessibility |
| 7 | Tue 27 Oct | F02, D02, buffer | Seed/demo, docs, CI, honest limitations, rehearsal |

P1 tasks (B08–B10, W05, M03, D01) only after the current gate is green and you still have time. P2 tasks (B11, B12) are not part of this delivery.

### 3.1 Gate 0 — Baseline

- `./mvnw.cmd verify` passes.
- `main` contains the baseline, and the workflows no longer trigger on `boilerplate`.
- The guides, the class diagram and the decision ADRs are merged (step 2b); no Modulith references remain outside ADR-0010's history.
- D0–D12 are approved or explicitly deferred.
- `.env` exists locally, is ignored, and is loaded in the IntelliJ run configuration.
- F01 branch exists.

Do not start F01 if Gate 0 is red.

### 3.2 Gate 1 — Foundations and auth

- Flyway applies `V1`; PostGIS extension exists.
- CI `build` is green: the jar starts in the smoke test with the new mandatory variables (e.g. `JWT_SECRET`) exported there.
- Register/login/refresh/logout work; HTTP Basic is gone.
- A citizen cannot call operator endpoints.
- No tokens/passwords in responses or logs.
- OpenAPI auth paths validate.

If auth is broken, stop. Every later feature depends on identity and permissions.

### 3.3 Gate 2 — Core incidents

- Create with and without category.
- Location is correct in PostGIS (longitude/latitude order).
- Evidence upload/download works; invalid type/size rejected; unauthorized access blocked.
- Cross-user access does not leak data.

If this gate is red, do not start lifecycle or UI work.

### 3.4 Gate 3 — Lifecycle, assignment, search

- Validate → priority → assign → start → resolve works.
- Invalid transition returns `409` and leaves no partial history.
- Audit rows contain actor/time/reason.
- Internal notes are invisible to citizens.
- Filters and bbox map results are correct.
- Reassignment preserves history.

### 3.5 Gate 4 — Context and client foundation

- Provider down: incident creation still returns `201`, context is `UNAVAILABLE`/`PENDING`.
- Context source/time/quality is visible.
- Stale is not presented as current.
- `pnpm api:check` passes; web/mobile can consume the generated client.
- Web login/session refresh works.

### 3.6 Gate 5 — Citizen clients

- Web (only if D11 keeps citizens on web): create with map/address/evidence, list, detail, timeline.
- Mobile: login, GPS, camera, submit, list, detail.
- Loading/empty/error/retry states work.
- No direct Nominatim/Overpass calls from clients.
- Mobile URL works on a real device/emulator.

### 3.7 Gate 6 — Operator and E2E

- Operator queue/filters/map.
- Detail actions work with reasons and conflict handling.
- Technician starts/resolves only assigned incidents.
- Citizen sees the resolved status and no internal notes.
- Playwright critical flow passes; axe smoke has no serious violations.
- Mobile manual checklist is signed off.

### 3.8 Gate 7 — Delivery

- Full backend verification, web/mobile checks, OpenAPI check.
- CI green.
- README/CHANGELOG/ADRs/deployment docs match reality.
- Demo rehearsed; known limitations written.
- No secrets or generated files staged.

---

## 4. Task-by-task playbook

For every task:

1. Re-read the task card in `guia-ia.md`.
2. Paste the prompt below.
3. Inspect the diff yourself.
4. Run the verification commands.
5. Commit/PR only if it passes.
6. Update the task tracker.

The prompts assume the AI has repository and terminal access. They are deliberately narrow. Do not add "also do X" unless X belongs to the same task.

### 4.1 P00 — Setup (you, not the AI)

Use section 1. Do not give P00 to the AI unless you want a read-only environment audit.

**Output:** baseline green, `main` updated, decisions recorded, branch ready.

---

### 4.2 F01 — Delivery spine

**Before you start:** approve D0, D1 and D10. Confirm Docker is running. Confirm no domain code exists.

**Prompt:**

```text
Read AGENTS.md, sections 0–5 of docs/guias/guia-ia.md, task card F01, the D0 and D10 decision records, and the current backend. Execute only F01: the delivery spine. Do not implement auth, incidents, attachments, clients, Docker, Redis, or AI.

Follow approved decisions D0 (no Spring Modulith; it was removed in 6a2c356), D1 (keep urbanpulse/; apps/packages come later) and D10 (Flyway via spring-boot-starter-flyway). Add the backend dependencies, profiles, Flyway V1, RFC 9457 error handling, pagination/clock/correlation support in package shared, the OpenAPI skeleton and a pinned Redocly lint step in the lint job. Do not create empty module packages, domain tables or endpoints. Do not add Springdoc unless I approve it. Do not change the existing security behavior beyond what F01 needs. The jar must still pass the CI smoke test. Run the F01 verification commands and report actual results. Do not commit or push.
```

**Inspect:**

- `V1__enable_postgis.sql` only enables PostGIS; no invented domain tables.
- No Spring Modulith dependency, `package-info.java` module annotation or `ModularityTest` came back; no empty module packages.
- Flyway comes from `spring-boot-starter-flyway` (D10), and the app log shows V1 applied.
- `docs/api/openapi.yaml` is the only contract (no generated spec competing with it); the Redocly version is pinned.
- No entity or endpoint was snuck in.
- OpenAPI has common schemas and placeholders, not fake implemented paths.
- `ddl-auto` remains `validate`.
- No secret has a default production value.

**Verify:**

```powershell
cd urbanpulse
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```

```powershell
npx @redocly/cli@<pinned version> lint docs/api/openapi.yaml
```

Push the branch and check that `build` (smoke test) is green, not only `test`.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat: add delivery spine and database migrations (F01)`. **Issue:** `[F01] Delivery spine`.

**If it fails:** ask for the exact failing command and log. Do not let it delete the Docker volume or disable Flyway.

---

### 4.3 B01 — Identity, authentication, authorization

**Before you start:** approve D3 (including role names), D4 (recorded as an ADR-0004 amendment) and D12 (class diagram in the repo). Confirm ADR-0004 is the authority, not the current HTTP Basic config.

**Prompt:**

```text
Read AGENTS.md, sections 0–5 of docs/guias/guia-ia.md, task card B01, ADR-0004 with its D4 amendment, and the User entity in the class diagram. Execute only B01. Approved decisions: D3 (one primary role; ADMIN includes operator actions; role names <the approved list>) and D4 (access JWT in memory; web refresh HttpOnly cookie; mobile refresh in SecureStore).

Replace HTTP Basic with JWT authentication. Implement users, refresh token rotation/revocation (including revocation when a user is disabled), register/login/refresh/logout, /users/me, role checks, and a simple login rate limit. Add migrations, tests, OpenAPI auth paths, and env example entries. If JWT_SECRET is mandatory in the default profile, export a throwaway value in the CI smoke-test step so build stays green. Do not implement incidents, admin CRUD, social login, password reset, or Redis. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- Password hash, refresh hash, and tokens never appear in responses.
- Login error is generic; duplicate registration is handled explicitly.
- Refresh rotation and logout revocation are tested.
- Cookie flags and origin check are correct.
- `@PreAuthorize`/service checks exist; `ADMIN` works as operator.
- JWT secret is required outside dev/test; `ci.yml` smoke step exports a throwaway one (not a real secret).
- Disabling a user revokes their refresh tokens.
- Role names match D3 in code, OpenAPI and seed.
- No wildcard CORS.

**Verify:**

```powershell
cd urbanpulse
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```

Manual with curl/Postman: register, login, `/users/me`, refresh, logout, reuse old refresh token. Test wrong password and a citizen calling an operator-only placeholder.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(auth): add JWT authentication and role checks (B01)`. **Issue:** `[B01] Authentication and authorization`.

**If it fails:** separate environment failures (port, Docker) from auth failures. Do not accept "tests skipped because Docker".

---

### 4.4 B02 — Incident registration and consultation

**Before you start:** approve D1/D2 only as needed. Confirm category is optional. Confirm the PostGIS generated-column design or accept the documented trigger fallback.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card B02, ADR-0003 and ADR-0010. Execute only B02.

Implement incident creation, role-scoped list/detail, owner update while REPORTED, optional category, PostGIS location, categories, pagination, validation, DTOs and tests. Use the generated geography column described in the guide; if PostgreSQL rejects it, use a trigger and report why. Do not implement lifecycle actions, evidence, assignment, comments, map/filter endpoints, duplicates, or external context. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- Category is nullable in DB and API.
- Client cannot set status/priority/reporter/context/audit fields.
- Location is required and validated; coordinates are not swapped.
- Citizen list/detail is scoped; operator sees all.
- No entity serialization.
- Pagination has a maximum and stable order.

**Verify:** backend `spotless:apply` + `verify`; manual create with/without category and cross-user access. Query the DB with `ST_X`/`ST_Y` or an integration test.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(incidents): add report registration and consultation (B02)`. **Issue:** `[B02] Incident core`.

**If it fails:** if PostGIS mapping is the blocker, do not fall back to plain lat/lon columns without approval. A trigger or native query is acceptable if tested.

---

### 4.5 B03 — Evidence attachments

**Before you start:** approve D9. Confirm the local storage path and the 10 MB limit.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card B03, and ADR-0013 (Proposed). Execute only B03. Approved evidence policy D9: JPEG/PNG/WebP/PDF, maximum 10 MB, magic-byte validation.

Implement the attachments table, StorageService/LocalFsStorage, authorized upload/list/download, safe headers, atomic write/cleanup, and tests. Do not implement video, thumbnails, virus scanning, S3, public links, or deletion. Do not change ADR-0013 status; propose it in the report. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- Binary is outside PostgreSQL.
- Key is a server-generated UUID, not the filename.
- Magic bytes are checked, not just extension/header.
- Unauthorized owner/technician access is blocked.
- Orphan cleanup is tested.
- No public URL; `nosniff`/`Content-Disposition` safe.

**Verify:** backend verify; manual valid image/PDF, renamed text, oversized file, cross-user download.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(evidence): add authorized attachment storage (B03)`. **Issue:** `[B03] Evidence attachments`.

**If it fails:** if the storage path is not writable, fix configuration; do not store bytes in the DB as a shortcut.

---

### 4.6 B04 — Lifecycle, priority, audit

**Before you start:** approve D2 and D3. Print the transition matrix and keep it visible. Confirm `REJECTED`/`CLOSED` terminal.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card B04, and ADR-0010. Execute only B04. Approved decision D2 is the status matrix in guia-ia.md section 4.7; D3 is one primary role.

Implement centralized transitions, justified priority, status history, append-only audit, allowedActions, and the explicit action endpoints. Do not implement assignment mechanics, comments, notifications, AI priority, or a generic set-status endpoint. Status + history + audit must be atomic in one transaction; invalid transitions must leave no partial state. Add migrations, tests, OpenAPI and CHANGELOG. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- One transition matrix, not duplicated in controllers/UI.
- Invalid transition → `409`, no history/audit rows.
- Stale version → `409`, no overwrite.
- Reason/resolution/justification rules are enforced.
- Audit has actor/time/reason/before/after.
- Citizen timeline hides internal notes.
- `allowedActions` matches role/status/assignment.

**Verify:** backend verify; manual validate, prioritize, reject without reason (rejected), illegal transition, stale update, audit rows.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(incidents): add lifecycle, priority and audit (B04)`. **Issue:** `[B04] Lifecycle and audit`.

**If it fails:** do not let the AI "simplify" the matrix to make tests pass. Fix the matrix decision with the human first.

---

### 4.7 B05 — Assignment and collaboration

**Before you start:** confirm department/technician rules and that internal notes must never reach citizens.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card B05, and the approved B04 matrix. Execute only B05.

Implement departments, append-only assignment history, assign/reassign/unassign, public/internal comments, request-information integration, permissions, migrations, tests, OpenAPI and CHANGELOG. Do not implement teams, workload balancing, auto-assignment, notifications, or admin screens. Assignment and status changes must be atomic with audit. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- Active assignment and incident current fields stay consistent.
- Reassignment closes the old row; history is preserved.
- Unassign only from `ASSIGNED`; technician must be valid/enabled.
- Technician can start only when assigned.
- Internal comments are absent for citizens, not just hidden in UI.
- References to users follow D0 and the class diagram (no relationship the diagram does not have).

**Verify:** backend verify; manual assign/reassign/unassign; wrong technician; comments as citizen/operator.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(incidents): add assignment and collaboration (B05)`. **Issue:** `[B05] Assignment and comments`.

**If it fails:** if assignment rules are ambiguous, stop and decide before coding.

---

### 4.8 B06 — Search, filters, map

**Before you start:** confirm bbox format and maximum area. Confirm role scopes.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card B06, and ADR-0003. Execute only B06.

Add incident filters, whitelisted sorting, bounded pagination, text/date/category/priority/department/assignee filters, bbox map endpoint, spatial indexes, role-scoped queries and tests. Do not implement duplicates, clustering, full-text search, analytics, or saved filters. Use PostGIS predicates, not in-memory filtering. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- Bbox is validated and bounded; invalid/crossing input rejected.
- Spatial index is used (report `EXPLAIN` evidence).
- No unbounded query or SQL interpolation of sort.
- Citizen/technician scopes cannot leak.
- Map DTO is minimal; no personal data.
- Pagination stable across pages.

**Verify:** backend verify; manual filters/bbox with seeded points.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(incidents): add search, filters and map (B06)`. **Issue:** `[B06] Search and map`.

**If it fails:** if `EXPLAIN` shows a sequential scan, fix the index/query before accepting.

---

### 4.9 B07 — External context and degradation

**Before you start:** approve D5. Confirm no Redis. Confirm the provider-down case is a demo requirement.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card B07, ADR-0002, ADR-0009 and ADR-0012 (Proposed). Execute only B07. Approved D5: in-process TTL cache; no Redis in P0.

Implement external observations, context status, Nominatim reverse/search adapters, async enrichment after commit, timeouts/one retry, manual refresh, context endpoints, fake-provider tests, env vars and CHANGELOG. Do not implement district/asset geometry, scheduled retry, Redis, AI, or real provider calls in tests. Incident creation must never block or roll back on provider failure. Run verification and report actual results. Do not commit or push.
```

**Inspect:**

- Incident creation commits before enrichment; no HTTP call inside the DB transaction.
- Provider down → `201` + `PENDING`/`UNAVAILABLE`, not false `AVAILABLE`.
- Observations have source, observed/ingested time, status, quality.
- Stale is distinct from current.
- Refresh is operator-only; citizens cannot trigger external calls.
- No external calls in automated tests.
- User-Agent/contact configured; no sensitive payload logs.

**Verify:** backend verify; manual invalid provider URL, create incident, inspect context; restore and refresh.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(externaldata): add context enrichment with degradation (B07)`. **Issue:** `[B07] External context`.

**If it fails:** if no live provider is reachable, keep the fake-based tests and state that no live integration was verified. Do not claim live success.

---

### 4.10 C01 — Monorepo and shared API client

**Before you start:** B03 is green; OpenAPI auth/incidents/evidence paths are frozen. Approve D1.

**Prompt:**

```text
Read ADR-0006, ADR-0007, ADR-0008, docs/guias/guia-ia.md, task card C01, and docs/api/openapi.yaml. Execute only C01.

Create the pnpm workspace, shared tsconfig/.npmrc, packages/domain, packages/api-client and packages/ui. Generate types from OpenAPI with openapi-typescript, implement a typed fetch wrapper with single-flight refresh and ProblemDetail mapping, add scripts and tests. Do not create app screens, change the backend, add deployment, or hand-write duplicate API DTOs. Run install/generate/check/typecheck/test and report actual results. Do not commit or push.
```

**Inspect:**

- `pnpm api:check` fails on drift and passes when generated.
- No `any` in public client APIs; no localStorage tokens.
- Domain package has no React dependency.
- UI package has no web-only dependency.
- Web/mobile can share the client.
- Lockfile and package manager are pinned; no mixed lockfiles.

**Verify:**

```powershell
pnpm install
pnpm api:generate
pnpm api:check
pnpm -r typecheck
pnpm -r test
```

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(client): add shared packages and generated API client (C01)`. **Issue:** `[C01] Shared client`.

**If it fails:** pin compatible versions; do not switch to hand-written DTOs or a different contract strategy without approval.

---

### 4.11 W01 — Web shell and authentication

**Before you start:** C01 and B01 are green. Confirm web refresh cookie behavior.

**Prompt:**

```text
Read ADR-0006, ADR-0007, docs/guias/guia-ia.md, task card W01, and the shared API client. Execute only W01.

Create the Vite React TS web shell with router, TanStack Query, CSS Modules, auth screens, protected routes, session refresh via HttpOnly cookie, logout, layout and tests. Do not implement incident screens, operator actions, admin, map or a component library. Access token stays in memory; no localStorage tokens. Run typecheck/test/build and report actual results. Do not commit or push.
```

**Inspect:**

- Login/register/logout; reload restores session through refresh.
- 401 triggers one refresh and retries; failure redirects.
- No tokens in URLs/localStorage; no wildcard CORS.
- Protected route works; forms accessible.
- Placeholder routes do not claim features.

**Verify:** `pnpm --filter ./apps/web typecheck`, `test`, `build`, `dev`; manual login/reload/logout.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(web): add authentication shell (W01)`. **Issue:** `[W01] Web auth`.

---

### 4.12 W02 — Web citizen flow

**Before you start:** check D11. With the recommended default (citizens on mobile) this task is P1: skip it and go to W03. Otherwise: W01, B03, B07 green; confirm map tile config and geocoding proxy.

**Prompt:**

```text
Read docs/guias/guia-ia.md, task card W02, and the web/API client. Execute only W02.

Implement the citizen create form with optional category, MapLibre location picker, backend geocoding search/reverse, evidence upload with progress/retry, my incidents list, detail/timeline, edit while REPORTED, and loading/empty/error/success states. Do not call Nominatim/Overpass directly. Do not implement operator actions, internal notes, duplicates or offline drafts. Run typecheck/test/build and report actual results. Do not commit or push.
```

**Inspect:**

- Category optional; server validation not bypassed.
- No direct provider calls; map attribution.
- Evidence client checks are hints only; server rejects invalid files.
- Duplicate submit prevented; retry works.
- Timeline hides internal notes.
- Accessibility labels/focus; responsive.

**Verify:** typecheck/test/build; manual create with map/address/evidence, list/detail, invalid file.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(web): add citizen report and tracking flow (W02)`. **Issue:** `[W02] Web citizen flow`.

---

### 4.13 W03 — Web operator queue and map

**Before you start:** W02 (or W01, if D11 moved W02 to P1) and B06 green. Confirm URL filter behavior.

**Prompt:**

```text
Read docs/guias/guia-ia.md, task card W03, and the web/API client. Execute only W03.

Implement the operator queue with URL-driven filters, pagination, list/map split view, bbox requests and role-scoped data. Do not implement detail/actions, admin, analytics or duplicates. Debounce queries, handle empty/error states, and keep filters shareable. Run typecheck/test/build and report actual results. Do not commit or push.
```

**Inspect:**

- Filters survive reload/back navigation.
- No unbounded/debounce-less requests.
- Map bbox matches list; attribution present.
- Empty/error states; operator-only access.
- No client-only permission assumption.

**Verify:** typecheck/test/build; manual filters/map.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(web): add operator queue and map (W03)`. **Issue:** `[W03] Operator queue`.

---

### 4.14 W04 — Web operator detail and actions

**Before you start:** W03, B04, B05 green. Keep the approved matrix visible.

**Prompt:**

```text
Read docs/guias/guia-ia.md, task card W04, the approved matrix, and the web/API client. Execute only W04.

Implement the operator/technician detail with allowedActions-driven actions, validate/reject/request information, justified priority, assign/reassign/unassign, start/resolve/close/reopen, public/internal comments, history/audit and context panel. Require reasons where specified, handle 409 conflicts, disable during submit, and invalidate queries. Backend remains the authority. Do not implement admin, notifications, AI, duplicates or bulk actions. Run typecheck/test/build and report actual results. Do not commit or push.
```

**Inspect:**

- Buttons reflect `allowedActions`, but forced requests still return `403`.
- Reasons/resolution/justification required.
- Conflict/stale version shows a safe reload path.
- Internal notes hidden from citizen views.
- Context shows source/time/quality/status.
- No optimistic update that hides failure.

**Verify:** typecheck/test/build; manual full lifecycle with operator + technician; force an invalid action.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(web): add operator incident actions (W04)`. **Issue:** `[W04] Operator detail`.

---

### 4.15 M01 — Mobile shell and authentication

**Before you start:** C01 and B01 green. Confirm the API URL strategy for emulator/device.

**Prompt:**

```text
Read ADR-0005, ADR-0007, docs/guias/guia-ia.md, task card M01, and the shared API client. Execute only M01.

Create the Expo TypeScript app with Expo Router, auth screens, tabs, SecureStore refresh, in-memory access token, API client, configurable EXPO_PUBLIC_API_URL and tests. Do not implement report screens, map, push or offline drafts. No localhost; document 10.0.2.2 for Android emulator and LAN IP for device. Run typecheck/test and report actual results. Do not commit or push.
```

**Inspect:**

- Refresh token only in SecureStore; no AsyncStorage tokens.
- App restart restores session; refresh works.
- API URL configurable; no hardcoded localhost.
- Permission requests are contextual.
- No secrets in bundle.

**Verify:** typecheck/test; manual Expo Go login/restart/logout on emulator/device.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(mobile): add authentication shell (M01)`. **Issue:** `[M01] Mobile auth`.

---

### 4.16 M02 — Mobile citizen flow

**Before you start:** M01, B03, B07 green. Decide D6: fallback map or 2-hour MapLibre spike.

**Prompt:**

```text
Read docs/guias/guia-ia.md, task card M02, and the shared client. Execute only M02. Approved D6: coordinate picker plus static OSM preview and open-in-maps for P0; embedded MapLibre is P1. You may spend at most 2 hours on a MapLibre dev-build spike; if it blocks, keep the fallback and report the limitation. Do not silently change ADR-0005.

Implement the report form with GPS permission, manual correction, optional category, camera/gallery evidence, upload retry, my reports list, detail/timeline, pull-to-refresh and error/empty states. Do not implement push, offline drafts, operator actions or video. Run typecheck/test and a manual checklist; report actual results. Do not commit or push.
```

**Inspect:**

- Location/camera permissions explained and denial handled.
- Accuracy shown; manual correction allowed.
- Evidence upload progress/retry; no duplicate submit.
- List/detail only own reports; internal notes hidden.
- No direct provider calls; API URL works on device.
- Fallback map documented if used.

**Verify:** typecheck/test; manual checklist on at least one platform.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `feat(mobile): add citizen report and tracking flow (M02)`. **Issue:** `[M02] Mobile citizen flow`.

---

### 4.17 Q01 — E2E, accessibility, quality

**Before you start:** W02, W04, M02 green. Prepare deterministic test data; no real providers.

**Prompt:**

```text
Read docs/guias/guia-ia.md, task card Q01, and the current web/mobile tests. Execute only Q01.

Add Playwright E2E for the citizen → operator → technician → citizen flow, unauthorized access checks, axe accessibility smoke, and a mobile manual checklist. Use test data without real external providers. Do not add load tests, cross-browser matrices or mobile E2E automation. Fix flakiness within this task; do not lower coverage or skip tests. Run the checks and report actual results. Do not commit or push.
```

**Inspect:**

- E2E covers the real critical path with roles.
- Test data setup is deterministic; no live Nominatim/Overpass.
- Accessibility smoke has no serious violations.
- Mobile checklist is signed off and limitations noted.
- No tests disabled/skipped to pass.

**Verify:** Playwright command from the app script; backend verify; web build; mobile tests.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `test(e2e): cover the critical citizen and operator flows (Q01)`. **Issue:** `[Q01] E2E and accessibility`.

---

### 4.18 F02 — Backend hardening, seed, final gate

**Before you start:** B07 is green. No P1 feature work in this task.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card F02, and the current backend. Execute only F02.

Add a dev-only seed for citizen/operator/technician/admin, categories, departments and sample incidents. Review OpenAPI/errors/pagination/indexes/N+1/logging. Run the full backend verification, coverage, SpotBugs and Spotless. Update backend docs/CHANGELOG. Do not add features, admin UI, AI, Docker or P1 work. Report actual results and known limitations. Do not commit or push.
```

**Inspect:**

- Seed is dev-only; no production seeding; placeholder passwords only.
- No new feature was sneaked in.
- Full verify green; coverage ≥ 0.80; no secrets.
- Sample data covers the demo states.
- Known limitations are listed.

**Verify:** backend verify; start app with dev profile and demo users; call health.

**PR title** (Conventional Commits, checked by `pr-title`; it becomes the squash commit): `chore(backend): add demo seed and final hardening (F02)`. **Issue:** `[F02] Backend final gate`.

---

### 4.19 D02 — CI, documentation, demo

**Before you start:** all P0 gates green, or you have explicitly cut scope.

**Prompt:**

```text
Read AGENTS.md, docs/guias/guia-ia.md, task card D02, the current CI, README, CHANGELOG and ADRs. Execute only D02.

Add web/mobile CI checks without renaming existing required checks (OpenAPI lint already exists since F01). Confirm boilerplate is no longer in the workflow triggers (removed in P00). Update README, development/deployment docs, CHANGELOG and ADR statuses only for implemented behavior. Fix or explicitly mark broken links. Produce a demo script and known limitations. Do not add features or claim unimplemented RFs. Run the checks and report actual results. Do not commit or push.
```

**Inspect:**

- CI jobs actually run and pass; no EAS credentials required.
- No required check renamed without ruleset update.
- README commands match reality; links work or are marked missing.
- ADR status changes have human approval.
- CHANGELOG has entries for P0 tasks.
- Demo script only claims implemented behavior.
- Known limitations are concrete.

**Verify:** push the PR and watch CI; run the documented commands locally; rehearse the demo.

**PR titles:** two PRs, one per concern: `ci: add web and mobile checks (D02)` and `docs: finalize delivery documentation (D02)`. **Issue:** `[D02] Delivery gate`.

---

### 4.20 P1 tasks (only if ahead)

Use the same protocol; keep them out of P0. Prompts:

#### B08 - District, neighborhood, and urban assets (P1)

```text
Read docs/guias/guia-ia.md, task card B08, ADR-0009 and the B07 model. Execute only B08. Timebox the OpenData Málaga catalogue audit to 2 hours. Do not fabricate districts, assets or confidence. If the data is unavailable, deliver the adapter contract and degraded path, document the limitation, and stop. Run verification and report actual results. Do not commit or push.
```

#### B09 - Duplicate suggestions (P1)

```text
Read docs/guias/guia-ia.md and task card B09. Execute only B09. Add bounded, explainable duplicate suggestions that never block creation and never auto-link. Do not leak cross-user data. Run verification and report actual results. Do not commit or push.
```

#### B10 - In-app notifications (P1)

```text
Read docs/guias/guia-ia.md and task card B10. Execute only B10. Add in-app notifications for assignment/status/comment events, mark-read authorization and idempotency. No email, push, Redis or delivery retries. Run verification and report actual results. Do not commit or push.
```

#### W05 - Web admin and analyst (P1)

```text
Read docs/guias/guia-ia.md and task card W05. Execute only W05. Add basic admin categories/departments/users and analyst aggregates/export only if the backend supports them. Analysts see no personal data or evidence. Run verification and report actual results. Do not commit or push.
```

#### M03 - Mobile map, push, and polish (P1)

```text
Read docs/guias/guia-ia.md and task card M03. Execute only M03. Add embedded MapLibre/push only if the dev build and B10 are ready. If blocked, keep the M02 fallback and report the limitation. Run verification and report actual results. Do not commit or push.
```

#### D01 - Docker, nginx, and compose (P1)

```text
Read docs/guias/guia-ia.md, task card D01, ADR-0011, ADR-0013 (Proposed) and ADR-0014 (Proposed). Execute only D01. Add multi-stage backend/web images, nginx, compose with internal db, healthchecks, evidence volume and env placeholders. Do not add TLS, Kubernetes, Terraform, Redis or mobile. Run `docker compose up --build` and report actual results. Do not commit or push.
```

### 4.21 P2 tasks (out of delivery)

B11 (analytics/reports) and B12 (AI/RAG) are out of scope for this delivery. Do not start them, do not add placeholder endpoints, and do not claim AI capabilities. If the human explicitly promotes one after D02, use its AI-guide card and record the scope change in the PR.

---

## 5. Review checklist

Run this for every PR. Do not rely on the AI's summary.

### 5.1 Scope and diff

- [ ] The diff contains only the task's files and concerns.
- [ ] No unrelated formatting, dependency upgrade, or refactor.
- [ ] No generated output (`target/`, `node_modules/`, `.expo/`, screenshots) staged.
- [ ] No secrets, `.env`, tokens, or personal data.
- [ ] The task card's out-of-scope list was respected.

### 5.2 Tests and verification

- [ ] New behavior has a test that would fail without the change.
- [ ] Permission/ownership failure cases are tested.
- [ ] Invalid input/error cases are tested.
- [ ] Integration tests use Testcontainers/fakes, not real providers.
- [ ] `./mvnw.cmd verify` was actually run and passed.
- [ ] Web/mobile typecheck/test/build were actually run and passed.
- [ ] Coverage did not drop below 0.80.
- [ ] No test was skipped/disabled to make the build green.

### 5.3 Security and privacy

- [ ] Every endpoint checks authentication and the specific permission.
- [ ] No IDOR on incidents, evidence, comments, history, context, notifications.
- [ ] Internal notes/personal data never reach citizens/analysts.
- [ ] Evidence validates size, magic bytes, and authorization; keys are server-generated.
- [ ] Refresh rotation/revocation works; no localStorage tokens; cookie flags correct.
- [ ] No wildcard CORS; cookie origin check.
- [ ] No secrets/tokens/password hashes/stack traces in responses or logs.
- [ ] External input is treated as data; model output cannot authorize actions.

### 5.4 Data and transactions

- [ ] Schema change is a new migration; no applied migration edited.
- [ ] Status + history + audit + assignment are atomic where required.
- [ ] Invalid transition leaves no partial state.
- [ ] Optimistic locking is tested.
- [ ] Cross-module relationships only where D0/the class diagram allow; no new module dependency cycle (the build no longer checks this, so you do).
- [ ] Latitude/longitude order and range tested.
- [ ] UTC instants; observed vs ingested time preserved.
- [ ] Unknown/unavailable is not zero/current.

### 5.5 API and contract

- [ ] OpenAPI updated for every path/field/error change.
- [ ] DTOs do not expose entities.
- [ ] Pagination default/max/stable order.
- [ ] ProblemDetail codes/status codes match the guide.
- [ ] `pnpm api:check` passes after contract changes.
- [ ] No endpoint exists only in code or only in the spec.
- [ ] Out-of-scope resources return `404`, forbidden actions on visible resources `403` (one rule everywhere).

### 5.6 UI and mobile

- [ ] Loading, empty, error, retry, success states.
- [ ] Duplicate-submit prevention.
- [ ] Accessible labels, focus, contrast, keyboard/touch targets.
- [ ] Map attribution; no direct provider calls.
- [ ] Mobile permissions explained; denial handled.
- [ ] Mobile API URL works on a device/emulator.
- [ ] Client hides actions but backend enforces them.

### 5.7 Documentation and delivery

- [ ] `CHANGELOG.md` updated under `## Not published` with the existing headings.
- [ ] PR title is a Conventional Commit ending in the task ID; body has `Closes #N`.
- [ ] README/docs match actual commands/behavior.
- [ ] New env vars in `.env.example`, and mandatory ones also in the CI smoke-test step.
- [ ] ADR statuses changed only with human approval.
- [ ] The report states what was not run/not implemented.
- [ ] Demo still works after the merge.

### 5.8 Reject immediately if

- Tests, coverage, Spotless, or SpotBugs were disabled/weakened.
- Category became mandatory.
- A status change or audit was implemented with an event instead of a transaction.
- A JPA relationship was added that the class diagram/D0 does not allow, or Spring Modulith was reintroduced without a team decision.
- Evidence is stored in the database or exposed via a public URL.
- Internal notes appear in a citizen response.
- AI/confidence/citations were fabricated.
- A secret, token, or `.env` was committed.
- A P1/P2 feature is claimed as P0 done.

---

## 6. Demo script

### 6.1 Before the demo

- [ ] `main` is clean and CI is green.
- [ ] Database/evidence volumes are in a known state.
- [ ] Dev seed users/categories/departments/incidents exist.
- [ ] Backend, web, and mobile can reach each other.
- [ ] External provider URL is valid for the happy path and easy to break for the degraded demo.
- [ ] Browser tabs/phone are prepared; notifications off.
- [ ] Screenshots or a short recording exist as fallback.
- [ ] Known limitations are written and honest.

### 6.2 Suggested accounts

Use the dev seeder placeholders from `guia-ia.md` section 9.5. Never use real passwords or production accounts.

### 6.3 Web/API demo

With D11's default, steps 1–3 and 7 are done on the **phone** (they then double as the mobile demo, section 6.4) and the rest on the web. If citizens also have a web flow, do all of them on the web.

1. **Citizen registers/logs in.** Show session persistence (reload the page on web, restart the app on mobile).
2. **Citizen creates an incident** with title, description, optional category, location, address, and a photo.
3. **Citizen opens tracking.** Show status, public timeline, evidence, and no internal notes.
4. **Operator logs in.** Show the queue, filters, and map.
5. **Operator opens the incident.** Validate it, set a priority with justification, and assign a technician.
6. **Technician logs in (web).** Show only the assigned incident, start it, add a resolution note/photo, and resolve it.
7. **Citizen refreshes.** Show resolved status and public history.
8. **Audit/history.** Show actor, time, reason, and status changes.
9. **Degraded mode.** Break the external provider URL (or stop the provider) and create a new incident. Show `201`, context `PENDING`/`UNAVAILABLE`, and no blocking. Restore and refresh context.
10. **Map/filters.** Show bbox and filtered queue.
11. **Tests/CI.** Show the actual commands/jobs, not a claim.

### 6.4 Mobile demo

1. Log in as citizen (SecureStore session).
2. Grant location/camera permissions; create a report with GPS and camera.
3. Show accuracy and manual correction.
4. Show my reports list and detail/timeline.
5. Show error/retry by disabling the network briefly.
6. If M03 was done, show the embedded map; otherwise show the fallback and say it is P1.

### 6.5 What to say about scope

Use a sentence like:

> "This delivery is a working vertical slice: authentication, reporting, evidence, lifecycle, assignment, audit, map and degraded external context on web and mobile. Notifications, duplicate suggestions, district/assets, analytics, reports and AI/RAG are prioritized as P1/P2 and are not claimed as complete. The README and known limitations list exactly what is in and out."

Do not say "everything is implemented" if P1/P2 tasks were cut. Do not show a feature that only exists as a mock.

### 6.6 Q&A preparation

Be ready to explain:

- Why the backend is a modular monolith, why the team dropped Spring Modulith (class-diagram relationships vs. enforced boundaries, D0), and how boundaries are kept without it (conventions + review).
- Why category is optional and why external sources never block a report.
- How status transitions and audit are atomic.
- How permissions are enforced server-side.
- Why Redis is not in this delivery (Proposed ADR; no demonstrated need).
- Why evidence is outside PostgreSQL.
- What is P1/P2 and why.
- What you would do next with another week.

---

## 7. Contingency and cut line

### 7.1 If a task fails repeatedly

1. Stop after the task timebox.
2. Ask for the exact failing command and log.
3. Separate environment failure from code failure.
4. If the task is P0 and blocks a gate, reduce its scope explicitly (for example, mobile map fallback) rather than letting it consume the week.
5. If the task is P1/P2, cut it and record it in known limitations.

Do not let the AI "fix" a failure by removing a test, lowering coverage, weakening a permission, or faking data.

### 7.2 If behind at Gate 2 (core incidents)

- Cut P2 entirely.
- Cut P1 notifications/duplicates/district/assets/admin/mobile map/Docker.
- Keep auth, create/read, evidence, lifecycle, assignment, audit.
- Move web/mobile to the minimal citizen + operator flow.
- If evidence is the blocker, keep upload but defer PDF/video and advanced preview; do not store in DB.

### 7.3 If behind at Gate 4 (context/client)

- Keep external context as `PENDING`/`UNAVAILABLE` with provenance; do not remove the degradation behavior.
- Cut district/assets (P1).
- Keep the generated client; cut nonessential shared UI.
- Web/mobile can use the minimal auth + report + list/detail.

### 7.4 If behind at Gate 6 (operator/E2E)

- Keep operator validate/reject/priority/assign/start/resolve and audit.
- Cut admin/analyst, notifications, duplicates, mobile embedded map, Docker.
- Keep the Playwright critical path; cut extra accessibility pages if necessary, but keep the smoke on login/create/detail.
- Do not cut permission tests.

### 7.5 If Docker or Expo blocks

- Docker: run backend from Maven and web from Vite; document that container deployment is P1/not verified.
- Expo MapLibre: keep the coordinate picker/static preview fallback; document the limitation.
- Expo device/API URL: use LAN IP, `10.0.2.2` for Android emulator, or an approved tunnel; never claim `localhost` works from a device.
- External provider: use fake-based tests and demonstrate degraded behavior; do not claim live integration.

### 7.6 Prompt for cutting scope

```text
We are behind schedule. Do not start new features. Stop all P1/P2 work. Revert uncommitted changes outside the current P0 task. Produce a cut list that preserves: authentication, incident creation/read, evidence, lifecycle/audit, assignment, permissions, degraded external context, citizen web/mobile core flow, and the tests for those. Update the demo script and known limitations to claim only what actually works. Do not fake anything.
```

### 7.7 What never gets cut

- Backend authorization and ownership/assignment checks.
- Transactional status/history/audit.
- Evidence validation and authorized download.
- Report creation during external-source failure.
- Internal-note privacy.
- Tests for those behaviors.
- Honest documentation of what is missing.

---

## 8. Prompt library

### 8.1 Onboarding (read-only)

Use section 2.4.

### 8.2 Per-task implementation

```text
Read AGENTS.md, sections 0–5 of docs/guias/guia-ia.md, task card <ID>, `git log --oneline -15`, and the current code/tests/OpenAPI. Execute only <ID>. Before coding, restate the goal, in-scope/out-of-scope, files you expect to touch, and any blocking decision. Wait if a decision is unresolved.

Implement the task with tests, migration/OpenAPI/env/CHANGELOG updates where applicable. Run the verification commands in the card. Do not commit, push, merge, or start another task. Report with the completion template.
```

### 8.3 Self-review

```text
Act as a reviewer, not an implementer. Compare your diff with task <ID>, its acceptance criteria, AGENTS.md, and the approved decisions. Do not change code yet. List blocking issues with file:line, missing tests, security/privacy risks, contract drift, transaction/boundary violations, and unrelated changes. End with APPROVE or REJECT and a short rationale.
```

### 8.4 Fix only the blockers

```text
Fix only the blocking issues listed in the review. Do not expand scope, do not change unrelated files, and do not weaken tests or checks. Add a regression test for each real defect. Re-run the task verification and report the actual results. Do not commit or push.
```

### 8.5 Scope-creep stop

```text
Stop. You are outside task <ID>. List every changed file that is not required by the task card. Revert only those changes, keep the task changes, and show `git status` and `git diff --stat`. Do not run destructive git commands. Then report and wait.
```

### 8.6 Commit preparation

```text
Prepare a commit for task <ID> only. Stage only the task's files. Propose a Conventional Commit message and a PR title/body using the repository template. Do not push or merge. Show the staged diff stat and any files you intentionally left unstaged.
```

If you allow the AI to commit, add: "Create the commit now, then stop. Do not push." If not, commit yourself.

### 8.7 Documentation/final pass

```text
Update only the documentation affected by the implemented behavior: README, development/deployment docs, OpenAPI, ADR statuses (only with human approval), CHANGELOG and known limitations. Do not claim unimplemented RFs. Do not invent test results. Mark broken references explicitly. Show the diff and stop.
```

### 8.8 Demo script

```text
Using only implemented and verified behavior, write a 5-minute demo script for web, API and mobile. Include setup, accounts, exact steps, expected observations, the degraded external-source case, and fallback if a component fails. List known limitations and what is P1/P2. Do not include features that are not implemented.
```

### 8.9 Explain a failure

```text
Explain the failure without changing code. Separate: (1) environment/tooling, (2) missing requirement/decision, (3) code defect. Cite the exact command, log line and file. Propose the smallest fix and the test that proves it. Do not run destructive commands.
```

### 8.10 Decision records (documentation only, P00)

```text
Documentation-only task. Read AGENTS.md, .github/adr-template.md, all files in docs/adr, sections 1 and 5 of docs/guias/guia-ia.md, and commit 6a2c356 (git show 6a2c356). Do not touch code or CI.

1. Draft decision records with status Proposed for the decisions I list here, each with context, alternatives, decision and consequences: <D0 as an amendment of ADR-0010, D10 as a new ADR, D4 as an amendment of ADR-0004, D2 as a new ADR with the transition matrix and its open points, D11 as a new ADR>. Use the next free ADR numbers. Keep the history of replaced text as the template requires; do not set any status to Accepted.
2. Remove the Spring Modulith / ModularityTest references from README.md, docs/ci.md, docs/ci-setup.md and ADR-0015, changing only those sentences.
3. Show `git diff --stat` and list anything you could not resolve. Do not commit or push.
```

---

## 9. Troubleshooting

### 9.1 Java and Maven

- `java --version` must show 25; `JAVA_HOME` must point to it.
- Use `.\mvnw.cmd` on Windows PowerShell, `./mvnw` on Linux/macOS/CI.
- If Spotless fails: `.\mvnw.cmd spotless:apply`, then re-stage/re-run.
- If the pre-commit hook behaves oddly, check `git config core.hooksPath`; the Maven profile installs `.githooks` locally.
- If coverage fails: add meaningful tests for new behavior; do not lower `jacoco.minimum.coverage`.

### 9.2 Docker and Testcontainers

- Docker must be running for `verify`; Testcontainers starts its own PostGIS.
- If port `5432` is busy, change `POSTGRES_PORT` in `.env` and adjust local URLs.
- If Testcontainers cannot pull the image, check network/disk; do not switch to a different image silently (CI lint checks parity).
- `docker compose down -v` deletes local database/evidence data. Use only when you are sure; back up first.

### 9.3 Flyway and PostGIS

- Never edit an applied migration; add a new one.
- If Flyway validation fails because the local volume is from an older schema, decide with the human whether to recreate the local volume; do not delete shared data.
- If the generated geography column is rejected, use the documented trigger fallback; keep PostGIS and tests.
- Coordinates: longitude is X, latitude is Y. Test with a known Málaga point.

### 9.4 Web and pnpm

- Use one package manager: pnpm. Do not create `package-lock.json` or `yarn.lock`.
- If Expo has resolution problems, check the root `.npmrc` `node-linker=hoisted` and clear the pnpm store/cache with the documented command.
- If the API returns 401 on boot, check the refresh cookie, `SameSite`, origin, and Vite proxy.
- If CORS appears, prefer the Vite proxy/nginx same-origin path; do not add wildcard CORS.
- If `pnpm api:check` fails, regenerate and commit the generated types with the contract change.

### 9.5 Mobile

- `localhost` does not point to your backend from a phone/emulator.
- Android emulator: `http://10.0.2.2:<port>/api/v1`.
- Physical device: use the host LAN IP and ensure both are on the same network/firewall allows it.
- SecureStore is for refresh tokens; access tokens stay in memory.
- MapLibre in Expo usually needs a development build, not Expo Go. If it blocks, use D6 fallback and document it.
- If camera/location permission is denied, show a useful explanation and allow manual correction; do not crash.

### 9.6 CI

- Current jobs: `lint`, `test`, `build` in `ci.yml`, and `pr-title` in `pr-title.yml`. Do not rename them without updating the required-check ruleset.
- If `pr-title` fails, edit the PR title to a Conventional Commit (`feat(scope): summary (B01)`); the check re-runs on edit.
- If `build` fails at "Smoke test" after adding a mandatory property, export it in that step (see `docs/ci.md`); the jar runs with the default profile and no `.env`.
- If `lint` fails on formatting, run `spotless:apply` and commit; do not disable the check.
- If `test` fails on coverage/SpotBugs, fix the code/tests; do not weaken gates.
- If the build job cannot start the jar, verify environment-only configuration and `/actuator/health`.
- Remove `boilerplate` from both workflows' triggers in the promotion PR itself, before merging it (P00, section 1.2).
- If a web/mobile CI job needs EAS credentials, redesign it to typecheck/test/build without credentials.

### 9.7 External providers

- Nominatim/Overpass public endpoints have rate limits and no uptime guarantee.
- Never call them from clients; use the backend proxy/cache.
- Do not retry 429 blindly; respect the policy.
- Automated tests use fakes/fixtures; live integration is explicit and manual.
- If the provider is down during the demo, show the degraded behavior; that is a feature, not a failure.

---

## 10. Appendix

### 10.1 Command cheat sheet

Backend:

```powershell
cd urbanpulse
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

```bash
cd urbanpulse
./mvnw spotless:apply
./mvnw verify
./mvnw spring-boot:run
```

Web (scripts created in C01/W01):

```powershell
pnpm install
pnpm --filter ./apps/web dev
pnpm --filter ./apps/web typecheck
pnpm --filter ./apps/web test
pnpm --filter ./apps/web build
```

Mobile (scripts created in C01/M01):

```powershell
pnpm --filter ./apps/mobile start
pnpm --filter ./apps/mobile typecheck
pnpm --filter ./apps/mobile test
```

Contract:

```powershell
pnpm api:generate
pnpm api:check
npx @redocly/cli lint docs/api/openapi.yaml
```

Docker (only if D01):

```powershell
docker compose up --build
```

### 10.2 URLs

| Service | URL |
|---|---|
| Backend | `http://localhost:8080` |
| Health | `http://localhost:8080/actuator/health` |
| Swagger UI (dev only, if enabled) | `http://localhost:8080/swagger-ui/index.html` |
| OpenAPI JSON (dev only) | `http://localhost:8080/v3/api-docs` |
| Web dev | `http://localhost:5173` |
| Web prod (D01) | `http://localhost:${HTTP_PORT:-8080}` |

Ports may differ; read the actual configuration.

### 10.3 Task tracker

Mark tasks as you finish them. Do not mark a task done until the gate/verification passes.

| Task | Status | Gate/notes |
|---|---|---|
| P00 Setup | [ ] | |
| F01 Delivery spine | [ ] | |
| B01 Auth | [ ] | |
| B02 Incident core | [ ] | |
| B03 Evidence | [ ] | |
| B04 Lifecycle/audit | [ ] | |
| B05 Assignment/comments | [ ] | |
| B06 Search/map | [ ] | |
| B07 External context | [ ] | |
| C01 Shared client | [ ] | |
| W01 Web auth | [ ] | |
| W02 Web citizen (P1 if D11 default) | [ ] | |
| W03 Web operator queue | [ ] | |
| W04 Web operator actions | [ ] | |
| M01 Mobile auth | [ ] | |
| M02 Mobile citizen | [ ] | |
| Q01 E2E/accessibility | [ ] | |
| F02 Backend gate | [ ] | |
| D02 CI/docs/demo | [ ] | |
| B08 District/assets (P1) | [ ] | |
| B09 Duplicates (P1) | [ ] | |
| B10 Notifications (P1) | [ ] | |
| W05 Admin/analyst (P1) | [ ] | |
| M03 Mobile map/push (P1) | [ ] | |
| D01 Docker/nginx (P1) | [ ] | |
| B11 Analytics (P2) | out | |
| B12 AI/RAG (P2) | out | |

### 10.4 RF checklist

| RF | Priority | Task | Done |
|---|---|---|---|
| RF01 Identity/access | P0 | B01, W01, M01 | [ ] |
| RF02 Roles/permissions | P0 | B01, W01, M01 | [ ] |
| RF03 Register incident | P0 | B02, W02, M02 | [ ] |
| RF04 Location | P0 | B02, B07, W02, M02 | [ ] |
| RF05 Evidence | P0 | B03, W02, M02 | [ ] |
| RF06 Consultation/tracking | P0 | B02, B06, W02, M02 | [ ] |
| RF07 Search/filters | P0 | B06, W03 | [ ] |
| RF08 Map | P0 | B06, W03, M02 | [ ] |
| RF09 Validation | P0 | B04, B05, W04 | [ ] |
| RF10 Priority | P0 | B04, W04 | [ ] |
| RF11 Assignment | P0 | B05, W04 | [ ] |
| RF12 Lifecycle | P0 | B04, W04 | [ ] |
| RF13 Collaboration | P0 | B05, W04 | [ ] |
| RF14 Notifications | P1 | B10, W05, M03 | [ ] |
| RF15 Statistics | P2 | B11, W05 | out |
| RF16 Duplicates | P1 | B09 | [ ] |
| RF17 Zonal analysis | P2 | B11 | out |
| RF18 Audit | P0 | B04, W04 | [ ] |
| RF19 Reports | P2 | B11, W05 | out |
| RF20 AI assistance | P2 | B12 | out |
| RF21 Neighborhood/district | P1 | B08 | [ ] |
| RF22 Urban asset | P1 | B08 | [ ] |
| RF23 External context | P0 | B07 | [ ] |
| RF24 Contextual view | P0/P1 | B07, W04 | [ ] |
| RF25 Historical correlation | P2 | B12 | out |
| RF26 Controlled degradation | P0 | B07 | [ ] |

### 10.5 Definition of done for the delivery

- P0 tasks are implemented and verified, or explicitly cut with the demo/limitations updated.
- Backend `verify`, web checks, mobile checks, OpenAPI check and CI are green.
- The citizen → operator → technician → citizen flow works on web and the citizen core flow works on mobile.
- Provider-down behavior is demonstrated and does not block report creation.
- Permissions, ownership, internal notes, evidence, and audit are verified.
- README/CHANGELOG/ADRs/deployment docs match reality.
- No secrets or generated files are committed.
- The demo is rehearsed and the known limitations are honest.

### 10.6 Final reminder

If only one thing goes right, make it the **P0 vertical slice with correct permissions, transactional history/audit, evidence, and degraded external context**. A narrow, honest, working delivery scores better than a broad set of half-implemented screens.
