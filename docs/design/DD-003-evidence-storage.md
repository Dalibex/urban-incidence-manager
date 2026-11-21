# DD-003 — Evidence in an external image service

The design of #341: where the photos of the incidents are stored once they leave the `evidence_data`
volume, how access to them stays authorized, and how the existing photos are moved. It proposes
Cloudinary as a temporary provider and compares it with Cloudflare R2. Its decisions are recorded
in ADR-0027 (#343) once the client validates them.

| Field | Value |
| --- | --- |
| Status | Draft |
| Authors | AlejandroCuetoDiaz (owner of #342), UrbanPulse team |
| Date of last revision | 2026-11-21 |
| Iteration | Sprint 3, *Complete app*: the photos in an external service, as the final delivery requires |
| Reviewers | Two team members, still to be named; then the client (the lecturers) in class |
| Links | #341 (feature), #342 (this document), #343 (ADR-0027), #344 (adapter and migration), #345 (tests); [ADR-0013](../adr/0013-evidence-storage.md); [OpenAPI](../api/openapi.yml); [C4 model](../architecture/workspace.dsl) |

## 1. Executive summary

Today the photos are files in a Docker volume on the only host, behind the `StorageService`
interface ([ADR-0013](../adr/0013-evidence-storage.md)), and the final delivery requires an external
service for them (#341). The proposal is a new `StorageService` implementation for **Cloudinary**,
with the photos uploaded as `authenticated` assets. **The backend keeps checking the permission and
streaming the bytes**, so the API, the clients and the database do not change, and no provider URL
reaches a client. Cloudinary is chosen as a **temporary** provider: the team expects access to AWS
S3 later (Q-1), so everything stays behind `StorageService`, a photo keeps its key in every
provider, and the migration process copies photos between any two providers. The main risk is that the Free plan
stores the photos in the United States with one account-wide secret (R-1, R-2), acceptable only
while the photos are test data.

## 2. Context and problem

- **Current verifiable situation.**
  - `StorageService` has four methods on whole byte arrays: `put`, `get`, `exists` and `delete`
    ([StorageService.java:14-23](../../backend/src/main/java/es/uma/urbanpulse/shared/storage/StorageService.java)).
    Its only implementation, `LocalFsStorage`, writes files named by a random UUID, and
    `StorageConfig` always creates it, with no switch by configuration.
  - The upload ([EvidenceService.java:103-122](../../backend/src/main/java/es/uma/urbanpulse/incidents/service/EvidenceService.java)):
    1. Checks the permission, the size, the type by magic bytes and the photo limit in a first
       transaction.
    2. Writes the file **outside any transaction**.
    3. Inserts the row in a second transaction that locks the incident, so two uploads at once
       respect the limit (#275).
    4. If the insert fails, deletes the file. There can be no row without a file; a file without a
       row stays behind if the process dies between steps 2 and 3, and no sweep exists.
  - The download, `GET /api/v1/attachments/{id}/content`, checks that the caller may see the incident
    and then streams the bytes from the backend, with `Cache-Control: no-store`
    ([EvidenceService.java:230-240](../../backend/src/main/java/es/uma/urbanpulse/incidents/service/EvidenceService.java)).
    The web gallery fetches them with the user's token and shows a failure with a retry per photo
    ([PhotoGallery.tsx:55-66](../../frontend/apps/web/src/incidents/PhotoGallery.tsx)). The mobile app
    only uploads; it retries after 1 s and 3 s on a network error or a `5xx`.
  - The rules: JPEG, PNG and WebP detected by content, 10 MB per file and 5 per incident,
    configurable ([ADR-0013](../adr/0013-evidence-storage.md), *Evidence Policy*). Who may see a
    photo is in [security.md](../security.md), the permission matrix.
  - In the deployment, the backend reaches the internet only through the Squid proxy, which lets
    through a list of hosts ([squid.conf](../../egress-proxy/squid.conf)). Each HTTP client sets the proxy itself;
    there is no JVM-wide proxy.
- **Problem.**
  - The final delivery requires an external service for the photos (#341).
  - While the photos live in a volume on one host, the backend cannot run a second replica: this is
    ADR-0013's own review trigger.
  - The volume must also be backed up by hand (`docker compose cp`, [deployment.md](../deployment.md),
    *Back Up and Restore*), and `docker compose down -v` deletes every photo.
- **Motivation.** It is a requirement of the final delivery, due in Sprint 3. ADR-0027 (#343) and the
  implementation (#344) are blocked until the client validates this design.

## 3. Goals, non-goals and scope

**Goals**

- G1. New photos are stored in an external service and survive the loss of the backend host.
- G2. A photo is served only to whom [security.md](../security.md) allows today; no permanent or
  public URL replaces that check.
- G3. The API, the web app and the mobile app do not change. If the client accepts Q-2, the only
  change is one new error code and its message in the mobile app.
- G4. A provider that is down, slow or limiting never prevents creating a report, and leaves no
  metadata row without a file.
- G5. The existing photos are moved with no loss by a one-off process that is safe to run twice, and
  the move can be undone.
- G6. Changing the provider again (to S3, Q-1) means writing one class and running the same process.

**Non-goals**

- N1. Who sees the photos uploaded by staff (#284) and the citizen's gallery in the mobile app
  (#279): they keep working on top of this.
- N2. Thumbnails, resizing or other transformations by the provider.
- N3. Stripping the EXIF metadata of the photos (Q-4).
- N4. A second backend replica: this design makes it possible, it does not build it.

**In scope:** `StorageService` (one new method, 7.2) and a new implementation, `EvidenceService` (where it reads the file),
the error mapping of a provider failure and its contract entry, the configuration and secrets, the
Squid allowlist, the migration process, and the documents that describe storage.

**Out of scope:** the database schema (the keys are kept), the clients, the permission rules, and
the services of the target architecture (DD-001, #328).

## 4. Requirements and architectural drivers

| ID | Type | Need or scenario | Acceptance criterion and evidence | Priority |
| --- | --- | --- | --- | --- |
| RF05 | Functional | A citizen attaches photos to their report; staff who may see the incident view them | Upload, list and download work against a fake of the provider (#345 T1, T2) and in the demo (T4) | High |
| RF-FD1 | Functional | The final delivery requires an external service for the photos (#341) | New photos are in the provider and not in the volume (#345 T1) | High |
| RQ-1 | Security | A user who may not see a photo asks for it, by the API or by a URL found elsewhere | `404` or `401` as today, and no provider URL or key in any response or log (#345 T6) | High |
| RQ-2 | Availability | The provider refuses connections, times out, answers `5xx` or `429` while a citizen reports | The report is created; the upload fails with a message the app can retry; no metadata row is left (#345 T5) | High |
| RQ-3 | Durability | The existing photos are moved, and the process is stopped halfway | Every row with a file has it in the provider with the same SHA-256; running it again finishes the work and changes nothing else (#345 T3) | High |
| RQ-4 | Performance | A phone uploads a 5 MB photo on the demo LAN | ADR-0013's accepted metric, p95 under 3 s, measured again with the provider in #344. It is a proposal to keep that threshold, not a measured fact (H-4) | Medium |
| RQ-5 | Performance | Staff open a photo in the web on the demo LAN | p95 of `GET /attachments/{id}/content` measured with the provider in #344 and recorded for DD-006 (#363). No threshold exists yet; the client may set one from that measurement | Medium |
| RC-1 | Constraint | Zero infrastructure cost (ADR-0013, context) | The Free plan covers storage and traffic (R-3) | High |
| RC-2 | Constraint | Credentials only in the environment, never in the bundles; the account is created by a team member, not an agent (#341, #342) | Review of `.env.example`, `docs/deployment.md` and the bundles | High |
| RC-3 | Constraint | The backend leaves only through the Squid allowlist ([squid.conf](../../egress-proxy/squid.conf)) | The provider's hosts are added to the list and checked in CI | High |
| RC-4 | Constraint | The provider is temporary; S3 is expected later (Q-1) | Nothing outside the new class depends on the provider (G6) | Medium |
| RC-5 | Constraint | The photos may show faces, plates and positions: personal data under the GDPR | Data location and terms recorded (R-1) | High |

## 5. Current architecture and dependencies

- **C4 context and containers.** The model is [workspace.dsl](../architecture/workspace.dsl). It
  draws the storage as the container `evidence_storage` ("S3-compatible storage later") and the
  volume as the deployment node `evidence_data`.
- **Current flow.**
  - The mobile app posts the photo (resized to 1920 px, JPEG) to
    `POST /api/v1/incidents/{id}/attachments`.
  - The backend validates it, writes it to the volume and inserts the row.
  - The web app reads it through `GET /api/v1/attachments/{id}/content`, which checks the permission
    and streams the bytes.
- **Dependencies and constraints.**
  - PostgreSQL holds the metadata, the key and the SHA-256 (`attachment`, migrations V3, V9 and V13).
  - The egress proxy (RC-3) and the photo limit with its row lock (#275).
  - The retries between components are to be decided by ADR-0026 (#330), which is not written yet.
  - The numeric keys of #369 come before #344; the storage key is a separate column and is not
    affected.

## 6. Alternatives considered

### 6.1. Provider

Free limits, terms and data location come from the providers' pages, checked on 2026-11-21 (see
[Sources](#sources)); they change, so ADR-0027 checks them again.

| Alternative | Description | Advantages | Drawbacks and risks | Available evidence |
| --- | --- | --- | --- | --- |
| A. Cloudinary Free | Image service with its own API; photos uploaded as `authenticated` assets | Named by the lecturers. No card. The free plan does not expire. 25 credits a month (1 credit = 1 GB stored, 1 GB served or 1,000 transformations). Refuses other formats (`allowed_formats`) and files over 10 MB on its own, a second check behind the backend. Maintained Java SDK | Stored in the **US**; EU storage is Enterprise only. **One account-wide key and secret**, with no keys limited to a folder. Signed delivery URLs **never expire**; expiring tokens are Advanced plan only. Its own API, not S3: the adapter is thrown away when moving to S3. Every view uses bandwidth credits. Limits are "soft": it warns, the page does not say whether uploads are blocked | Pricing and plan comparison pages, access-control docs, terms, sub-processors |
| B. Cloudflare R2 | S3-compatible object storage | **EU jurisdiction** fixed on the bucket. 10 GB free, **free egress**, so streaming through the backend costs nothing. Tokens limited to one bucket and to read or write. The S3 API: **the same adapter would later serve AWS S3** (G6). Presigned URLs up to 7 days, if they were ever wanted | Not an image service: no image checks of its own (the backend already does them). Enabling it goes through a checkout; whether it asks for a card, and whether the EU jurisdiction is free, could not be confirmed on official pages. Usage over the free tier is billed, not blocked. The lecturers may expect an image service | Pricing, data location, API tokens and AWS SDK for Java pages |
| C. AWS S3 now | The expected final provider, in a team account | The final provider from the start; any region (Spain, `eu-south-2`); IAM policies | The team has no access yet (Q-1). New accounts since 15 July 2025 get credits; the Free plan ends after 6 months or when the credits run out, and an account not upgraded within 90 days is closed and its content deleted | AWS Free Tier terms and FAQ |
| D. Keep the volume (baseline) | ADR-0013 as it is | Nothing to build; no third party | Does not meet the final-delivery requirement; one host, manual backups, no replicas | ADR-0013 |

Backblaze B2 (10 GB free, EU region chosen when the account is created, keys limited to a bucket and
a prefix) is close to B and adds nothing that B lacks. Self-hosted MinIO was not considered: it is
not an external service, and its community repository has been archived and unmaintained since
April 2026.

**Comparison criteria:** meeting the final-delivery requirement, privacy and data location, keeping
access authorized, cost on the free tier, the work to change to S3 later, and the work for the team
now.

**Proposed choice and why: A, Cloudinary, as a temporary provider.**

- It is the service the lecturers named, it needs no card, and its free plan does not expire.
- It also refuses wrong formats and oversized files on its own, a second line behind the backend.
- Its two serious weaknesses are acceptable only under conditions this design sets:
  - Data in the US with one account-wide secret: acceptable only while the photos are test data
    (R-1, R-2).
  - URLs that never expire: harmless because no URL leaves the backend (section 6.2).
- The honest cost of A against B: B would already be the S3 adapter that the move to AWS needs, and
  would keep the photos in the EU. With A, the Cloudinary class and its tests are thrown away at
  that move. The migration process, the error mapping and the tests of behaviour are kept.
- If the client gives more weight to data location or to the later move to S3 than to using an image
  service, B is the choice, and the rest of this design stays the same.

### 6.2. How access stays authorized

| Alternative | Description | Advantages | Drawbacks and risks | Available evidence |
| --- | --- | --- | --- | --- |
| X. The backend streams the file | As today: check the permission, read the file from the provider, send the bytes | No contract or client change (G3). No URL reaches anyone (RQ-1). Works the same with any provider (G6) | Every view goes through the backend: one more round trip, to a provider in the US, before the first byte. The provider's CDN cache is not used, so each view is one transfer from the provider, as with Y; the leg from the backend to the client is the host's own traffic. The whole file is held in memory, at most 10 MB, as today | The current code ([EvidenceService.java:230-240](../../backend/src/main/java/es/uma/urbanpulse/incidents/service/EvidenceService.java)) |
| Y. Redirect to a short-lived signed URL | Check the permission, then answer `302` to a provider URL valid for a few minutes | No bytes through the backend; provider caching | On Cloudinary Free a signed URL **never expires**: a leaked URL works forever. An expiring one needs the paid Advanced plan, or `private_download_url`, which costs twice the bandwidth and is not cached. Changes the contract and both clients. Anyone holding the URL can use it until it expires | Cloudinary access-control docs; R2 and S3 presigned URLs (up to 7 days) |
| Z. Public delivery with an unguessable name | Upload as a public asset with a random name | Simplest | A permanent public URL as access control, which [AGENTS.md](../../AGENTS.md), section 8, forbids | — |

**Proposed choice: X.**

- It keeps today's guarantees and contract, and it hides the provider: the property that makes the
  later change cheap.
- Its cost, more latency and traffic through the backend, is small for photos of at most 10 MB seen
  by a few staff members.
- The latency is measured in RQ-5. If it or the traffic ever matters, Y can be added behind the same
  endpoint with an expiring provider (R2 or S3).

### 6.3. Files left without a row

| Alternative | Description | Advantages | Drawbacks |
| --- | --- | --- | --- |
| O1. File first, row after, plus a cleanup (proposed) | Keep today's order; a cleanup mode of the one-off process deletes provider files with no row that are older than one hour | No orphan row by construction; no schema change; the cleanup is the sweep ADR-0013 promised and never built | A file can sit orphaned until the cleanup runs. Listing the provider uses Cloudinary's Admin API, whose free-plan rate limit is still to be checked (Q-5). A cleanup run against the wrong database would delete good photos, so it needs the safeguards of 7.4 |
| O2. A pending row first | Insert the row as pending, upload, then mark it ready | Every file is traceable from the database | Schema change and a status every read must filter; a pending row is the orphan row #341 forbids, unless it is hidden and cleaned too |
| O3. Accept orphan files | Do nothing | Nothing to build | Storage credits leak; breaks #341's "no orphan file without a way to clean it" |

## 7. Proposed design

### 7.1. Overview and C4 diagrams

- **Context (L1):** Cloudinary appears as a new external system that stores the photos.
- **Containers (L2):** the container `evidence_storage` and the deployment node `evidence_data` give
  way to the external system Cloudinary, reached by the backend through the egress proxy. ADR-0027
  (#343) updates the model; the draw.io file is generated from the DSL.
- **Components (L3):** no change: `EvidenceService` → `StorageService` → `CloudinaryStorage` (new)
  or `LocalFsStorage` (kept for development and tests), chosen by configuration.
- **Upload sequence (proposed):**
  1. Transaction 1 runs the checks, as today.
  2. `CloudinaryStorage.put` generates the key (a UUID, as `LocalFsStorage` does today).
  3. It uploads with public id `<prefix>/<key>`, type `authenticated`, `overwrite: false` and
     `allowed_formats: jpg, png, webp`. All of this happens **outside** any transaction.
  4. Transaction 2 locks the incident and inserts the row, as today.
  5. If step 4 fails, the backend deletes the upload; if that delete fails, the cleanup removes the
     file later (O1).
- **Download sequence (proposed):**
  1. A read-only transaction checks the permission and reads the key and the type, and **ends**.
  2. Outside it, the backend builds the signed `authenticated` delivery URL of the original, fetches
     it with a timeout and streams the bytes with the stored type and `no-store`.
  3. The signed URL lives only inside that call. Today the file is read inside the transaction
     (#344 must change that).

### 7.2. Interfaces and contracts

- **Public API:** unchanged paths, bodies and permission rules.
- **One addition, open until the client answers Q-2:** a provider that is down, slow or limiting
  would answer **`503` with the code `STORAGE_UNAVAILABLE`** on upload and download, instead of
  today's generic `500`, so the clients can tell "try again later" from a bug.
  - The mobile app already retries on `5xx`. Its message for a `5xx` today asks the citizen to try
    again with a better connection, which misleads during a provider outage. If `503` is accepted,
    the app gets its own message for this code ("the report is saved; the photo could not be stored
    now, try again later").
  - The web gallery already shows a failure with a retry per photo.
  - #344 adds the code to [openapi.yml](../api/openapi.yml) following [api.md](../api.md).
- **The other errors are unchanged:**
  - `401` with no token, `403` for the analyst, and `404` for an incident or photo the caller may
    not see.
  - `409 ATTACHMENT_NOT_ALLOWED` for a citizen once the incident has left `REPORTED`.
  - `413` for size, and `422` for an empty file, a wrong type or too many photos.
  - #341, #344 and #345 mention `415`, which the API has never answered (Q-3). Open PR #409
    proposes `400` for a malformed multipart body; it does not depend on the storage.
- **`StorageService`:** keeps its four methods and their meaning, and gains **one**:
  - `put(key, content)` stores the content under a key that a `StorageService` generated earlier.
  - It accepts only keys in the form the storage generates (a canonical UUID), so a key still never
    comes from user input. Storing different content under a key that already exists is an error.
  - Only the migration process calls it. It is what lets a photo keep its key in every provider, so
    the database needs no update when photos move (7.3, section 9).
  - Uploads keep calling `put(content)`, which generates the key.
- **Provider failures** are a `StorageException`, and the new implementation tells three kinds apart:
  - *Unavailable:* connection refused, timeout, `5xx`, `429` or Cloudinary's `420` rate limit.
    These map to `503` if Q-2 is accepted.
  - *File refused:* only the documented refusals of a file the backend accepted, such as too many
    megapixels. These map to `422` with a field error on `file`.
  - *Misconfigured:* `401`, `403` or any other `4xx`, for example a rotated secret. These stay a
    `500` and are logged as an error, because the citizen can do nothing about them.
- **Idempotence:**
  - Upload retries are already answered from the SHA-256 without touching the provider.
  - Every write names its public id and uses `overwrite: false`, so a repeated write (a retried
    migration step, or a request that timed out after the provider stored it) cannot create two
    files or replace one.
  - Delete is idempotent (`destroy` of a missing asset is not an error).

### 7.3. Data model and management

- **Entities:** `attachment` keeps its columns and its unique `storage_key`.
  - The key stays the provider-independent UUID. The adapter adds a fixed prefix (for example
    `urbanpulse/evidence/`).
  - Thanks to `put(key, content)` (7.2), **no migration of the schema and no key update are
    needed**. That also makes the later move to S3 a copy and nothing else.
  - The alternative, kept by #344's current text, is to copy with `put(content)` and update each
    row's key in its own transaction. It works too, but every move and every rollback then rewrites
    the keys, and a crash between the copy and the update leaves a file the cleanup must find. #344
    is updated to follow whichever ADR-0027 accepts.
- **Ownership:** the backend is the only writer and reader; the account belongs to the team (RC-2).
- **Consistency:** a row exists only if its file was stored (today's order, kept). A file without a
  row is cleaned by O1.
- **Retention:** as today, photos are not deleted while their incident exists; the database stays
  the record of which files must exist.
- **Recovery:**
  - The provider keeps the photos instead of the host.
  - The one-off process (section 9) can also copy every photo from the provider back to a folder.
    That is the backup and the way out of Cloudinary.
  - It replaces the manual `docker compose cp` of the volume.
- **Privacy:** see 7.5 and R-1.

### 7.4. Failures, concurrency and resilience

- **Timeouts:** every provider call has a connection timeout and a total timeout. The proposed
  starting values are 2 s and 15 s, below the mobile app's 60 s stall timeout. They are proposals;
  ADR-0027 fixes them after measuring in #344.
- **Retries:**
  - **Uploads are not retried by the backend:** the mobile app already retries twice, and the
    backend answers a repeated photo from its SHA-256. Retrying on both sides would multiply the
    wait.
  - Downloads and the migration process retry at most once, with a short pause, and only when the
    provider is unavailable (7.2), honouring `Retry-After` when it fits in the budget. It is safe
    because those calls are idempotent.
  - It follows ADR-0026 (#330) once that is accepted.
- **What the citizen sees with the provider down:**
  - Creating the report never touches the provider: the photos are separate requests, so the
    report is created and only the photos fail (RQ-2).
  - A provider that refuses connections fails at once. A provider that does not answer costs each
    of the app's three attempts its total timeout: with the starting values, about 15 s × 3 plus the
    1 s and 3 s pauses, close to 50 s of waiting before the failure message.
  - Whether that wait is acceptable, or the upload timeout should be shorter, is for the client to
    say (Q-8).
- **Degradation:**
  - The incident list and detail come from PostgreSQL and keep working; only each photo shows its
    failure and retry in the web.
  - `/actuator/health` must **not** depend on the provider. Otherwise a Cloudinary outage would mark
    the whole backend unhealthy.
- **Circuit breaker:** not now. There is one backend, little traffic, and short timeouts already
  bound the wait. It is reconsidered if ADR-0026 adopts one, or if measurements show requests piling
  up during an outage.
- **Concurrency:**
  - The photo limit keeps its incident row lock (#275).
  - The loser of a race deletes its own upload, as today. With a remote provider that delete can
    fail more often, so the cleanup catches it.
- **The cleanup (O1) is guarded:** it deletes only files under the configured prefix with no row
  and older than one hour, it lists what it would delete unless it is run with an explicit delete
  option, and it stops above a maximum number of deletions per run. A cleanup run against the wrong
  database would otherwise delete good photos. Only the deployment uses the real account; local
  development and the tests use `LocalFsStorage` or a fake.
- **Something stops halfway:**
  - The backend stops during an upload: the same cases as today (section 2). There is no row
    without a file, and a file without a row is removed by the cleanup.
  - The mobile app closes during an upload: no row is written, as today.
  - The migration stops: running it again resumes it (RQ-3).
- **A file the backend accepts but the provider refuses:** for example an image over Cloudinary's
  25-megapixel limit that is still under 10 MB. It answers `422` with a field error on `file`
  rather than `503`, and nothing is stored (Q-7).

### 7.5. Security

- **Authorization:** unchanged, in `EvidenceService`, before any provider call. A photo the caller
  may not see answers `404`, as today.
- **No URL leaves the backend:**
  - Photos are uploaded as `authenticated` assets, so neither the original nor any derived version
    is served without a signature.
  - The signed delivery URL is never returned, stored or logged. Logs carry the key, the outcome and
    the latency only.
  - Because Cloudinary's signed URLs do not expire, a logged URL would be a permanent leak. This rule
    is tested (#345 T6).
- **Secrets:**
  - One variable with the key and secret (`CLOUDINARY_URL`, a name to confirm in #344). It lives in
    the host's `.env` only: listed empty in `.env.example` and marked as a secret in
    [deployment.md](../deployment.md).
  - Never in a `VITE_*` or `EXPO_PUBLIC_*` variable, so never in a bundle (RC-2).
- **The account:**
  - Created by a team member, not by an agent, used only for UrbanPulse, with multi-factor sign-in.
  - Its secret is rotated if it ever leaks. Because the secret can do anything in the account
    (R-2), the account holds nothing else.
- **Data location and terms:**
  - The Free plan stores in the US. Cloudinary relies on the EU-US Data Privacy Framework. Its
    processor terms are written for paid subscriptions, and it is unclear whether they cover the
    Free plan (Q-6).
  - The team keeps ownership of the content under the terms.
  - Conclusion: **only test photos, no real citizen's photos, while the provider is Cloudinary
    Free** (R-1).
  - The provider's moderation and analysis add-ons stay off.
- **Metadata of the photos:**
  - The provider stores the bytes as uploaded, as the volume does today.
  - The mobile app re-encodes every photo before sending it, which probably drops the EXIF block
    with the position (H-3, unverified). A photo sent straight to the API keeps it.
  - Stripping it on the server is a separate decision (Q-4); what changes now is that the file goes
    to a third party.
- **Egress:** only `api.cloudinary.com` (upload and administration) and `res.cloudinary.com`
  (delivery) are added to the Squid allowlist (H-1). The SDK is given the proxy explicitly, like the
  weather client (H-2).

### 7.6. Operation and deployment

- **Topology:** the same Compose deployment on one host. The backend stops needing the
  `evidence_data` volume once the migration is verified and the rollback window has passed; a later
  pull request removes it.
- **Configuration (names to confirm in #344):**
  - `STORAGE_PROVIDER`: `local` by default for development and tests, `cloudinary` in the deployment.
  - `CLOUDINARY_URL`: secret.
  - `CLOUDINARY_PROXY=http://egress-proxy:3128`, set in Compose like `OPEN_METEO_PROXY`.
  - The prefix of the keys in the account.
- **No AWS and no IaC** for this provider. When S3 arrives (Q-1), its bucket, region and IAM user
  are its own decision.
- **Observability:**
  - Each provider call logs its operation, key, outcome and duration.
  - The upload and download latencies feed the SLIs of DD-006 (#363).
  - Credit usage is checked in Cloudinary's dashboard before each demo.
- **Scaling:** with no files on the host, a second backend replica becomes possible: ADR-0013's
  review trigger, now met.
- **Rollback:** switching `STORAGE_PROVIDER` back to `local`, after copying back the photos uploaded
  since the switch (section 9).
- **Cost:** zero on the Free plan within 25 credits a month (R-3).
  - Each view of a photo costs its size in bandwidth credits, and each stored photo its size in
    storage credits.
  - The load tests of #371 and #367 run against the local storage or a fake, never against
    Cloudinary. A test that downloads photos in a loop would spend the month's credits quickly.

## 8. Decisions and trade-offs

| Proposed decision | Benefit | Cost or consequence | Requires an ADR? |
| --- | --- | --- | --- |
| Cloudinary Free as a temporary provider (6.1) | Meets the requirement with the service the lecturers named, at no cost, with its own format and size checks | Data in the US and an account-wide secret: test photos only. An adapter thrown away at the move to S3 | Yes, ADR-0027 (#343), which supersedes ADR-0013 |
| The backend streams the photos (6.2) | No contract or client change; no URL leaves the backend; works with any provider | One more round trip per view, and no provider CDN cache | Yes, in ADR-0027 |
| Keep the keys with `put(key, content)`; the adapter adds the prefix (7.2, 7.3) | No schema change; moving provider or rolling back is only a copy | One more method in `StorageService`; the prefix lives in configuration and must not change while photos exist | In ADR-0027 |
| File first, row after, plus a guarded cleanup (6.3, 7.4) | No orphan rows by construction; orphan files can be cleaned | A cleanup to run, its safeguards, and the Admin API rate limit to respect | In ADR-0027 |
| `503 STORAGE_UNAVAILABLE` for a provider failure (7.2), if the client accepts Q-2 | The clients can tell "retry later" from a bug | One more error in the contract and one more message in the mobile app | No: a contract change in #344 |
| Timeouts; the app retries uploads, the backend retries only idempotent reads; no circuit breaker yet (7.4) | Bounded waits with little machinery | An outage that does not answer still costs each attempt its timeout (Q-8) | Follows ADR-0026 (#330) |

ADR-0013 is accepted. ADR-0026 (#330) and ADR-0027 (#343) are not written yet: nothing in this
document is a decision until ADR-0027 is accepted.

## 9. Implementation and migration plan

1. **ADR-0027 (#343)** records the choices this document validates, and updates the C4 model.
2. **#369** (numeric keys) lands first, as #344 requires.
3. **#344, the adapter:**
   - `CloudinaryStorage` behind `StorageService`, chosen by `STORAGE_PROVIDER`.
   - `put(key, content)` in `StorageService` and both implementations.
   - The download read moved out of the transaction, and the error mapping of 7.2, with its contract
     entry if Q-2 is accepted.
   - The Squid hosts, with their CI check, and the documents of #341.
4. **#345, the tests** against a fake HTTP server standing in for Cloudinary; nothing calls the real
   one in CI.
5. **The one-off process** (Twelve-Factor, factor XII), run as a separate command of the same
   artifact:
   - It copies every photo from a source `StorageService` to a target one: volume to Cloudinary now,
     Cloudinary to S3 later, Cloudinary back to a folder for a backup or a rollback.
   - For each row it reads the file from the source and stores it in the target with
     `put(key, content)`, under the same key.
   - It skips a key the target already has, which makes it safe to run twice. An upload either
     completes or leaves nothing, so an existing key holds a whole file.
   - It reads back each photo it copies and compares its SHA-256 with the row's, so every photo is
     checked once, when it is copied.
   - It reports the rows with no file behind them without failing. The dev and perf seed rows have
     none, and the perf keys (`perf/N`) are not UUIDs, which `LocalFsStorage` refuses.
   - It ends with counts: copied, already there, missing.
   - Its cleanup mode is O1.
6. **The switch in the deployment:**
   1. Confirm that the deployment holds only test photos (R-1). If it holds real citizens' photos,
      stop and take the question to the client.
   2. Stop the backend.
   3. Run the process.
   4. Check the counts.
   5. Set `STORAGE_PROVIDER=cloudinary`.
   6. Start the backend and open a photo in the web (#345 T4).
   - The volume is kept untouched as the fallback.
7. **Rollback:**
   1. Stop the backend, so no photo is uploaded during the copy.
   2. Run the process from Cloudinary to the volume, which copies back the photos uploaded since the
      switch.
   3. Set `STORAGE_PROVIDER=local`.
   4. Start the backend.
   - The database needs nothing: the keys are the same.
8. **Cleanup:** once the rollback window has passed, a later pull request removes the volume from
   Compose and the backup steps from [deployment.md](../deployment.md).

**Dependencies:** #343 (accepted), #369, ADR-0026 (#330) for the retry rules, and the account created
by a team member.

**Impact on earlier versions:** none for the clients, unless Q-2 adds one error code and its mobile
message. The database is unchanged. The ADR-0013 metric is measured again (RQ-4).

## 10. Verification and evaluation strategy

| Requirement | Test or experiment | Environment and data | Metric or threshold | Expected evidence |
| --- | --- | --- | --- | --- |
| RF05, RF-FD1 | #345 T1, T2 | Testcontainers PostgreSQL and a fake HTTP Cloudinary | Upload stored, download after the permission check | CI test |
| RQ-1 | #345 T6; a log assertion | Same | `404`/`401`; no `res.cloudinary.com` or signature in responses or logs | CI test |
| RQ-2 | #345 T5 with connection refused, timeout, `503` and `429` from the fake | Same | Report created; the upload fails (`503 STORAGE_UNAVAILABLE` if Q-2 is accepted); no row | CI test |
| Error mapping (7.2) | The fake answers `401` and a file refusal | Same | `500` logged as an error; `422` on `file` | CI test |
| RQ-3 | #345 T3, plus stopping the process halfway and running it again | Same, with a volume of photos | Every SHA-256 matches; a second run copies nothing | CI test |
| Limit #275 | #345 T8 | Same | The limit holds | CI test |
| RQ-4 | Timed uploads of a 5 MB photo from a phone, at least 20 runs | Deployment on the demo LAN with the real Cloudinary account | p95 under 3 s (proposal) | Measurement in the #344 PR |
| RQ-5 | Timed downloads of the same photo from the web, at least 20 runs | Same | p95 recorded, no threshold yet | Measurement in the #344 PR |
| Demo | #345 T4 | Deployment, real account, test photo | The photo shows in the web | Manual evidence in the #344 PR |
| RC-3 | Squid log while uploading and viewing a photo | Deployment | Only the two hosts used | Manual evidence |

**What only the real cloud can show:** the latency from the demo LAN to Cloudinary, the hosts and
the proxy actually used, that the original is returned byte for byte (H-5), and credit consumption.
**The fake covers:** permissions, errors, timeouts, idempotence, the migration and concurrency.

## 11. Risks, hypotheses and open questions

| ID | Type | Description | Impact | Action and owner |
| --- | --- | --- | --- | --- |
| H-1 | Hypothesis | `api.cloudinary.com` and `res.cloudinary.com` are the only hosts the backend needs | Calls blocked by Squid | Check the Squid log in #344 |
| H-2 | Hypothesis | The Cloudinary Java SDK takes an explicit proxy (its source sets a proxy host and port from its configuration) | No route out | Check in #344; otherwise a thin HTTP client of our own |
| H-3 | Hypothesis | The mobile app's re-encoding drops the EXIF block with the position | Positions sent to a third party | Inspect an uploaded photo in #344; feeds Q-4 |
| H-4 | Hypothesis | Uploading to a provider in the US stays within ADR-0013's p95 of 3 s for 5 MB | Slower uploads for citizens | Measure in #344 (RQ-4) |
| H-5 | Hypothesis | Delivering the original with no transformation returns the uploaded bytes unchanged | The SHA-256 check of the migration fails | The migration verifies it on every photo |
| R-1 | Risk | Photos stored in the US under Free-plan terms (GDPR) | Personal data outside the EU | Test photos only while on Cloudinary Free; move to the EU (S3 or R2) before real data |
| R-2 | Risk | The single API secret can read and delete everything | A leak exposes or destroys every photo | A dedicated account with MFA; secret only in `.env`, never logged; rotate on a leak |
| R-3 | Risk | The 25 monthly credits run out (load tests, demos, every view of a photo) | Warnings, perhaps blocked uploads | Load tests on a fake or local storage; check usage before demos |
| R-4 | Risk | The Cloudinary adapter is thrown away when moving to S3 | Repeated work | Keep everything behind `StorageService` and the copy process generic (G6) |
| R-5 | Risk | Orphan files after a crash or a failed delete | Credits wasted | The cleanup mode (O1) |
| R-6 | Risk | The Free plan changes its terms or the account is deactivated | Loss of access to the photos | The copy process exports them; keep the volume until the switch is verified |
| Q-1 | Question | Will the lecturer give the team access to AWS S3, and when? | How long Cloudinary lives, and whether B or C should be chosen instead | Ask the client in class |
| Q-2 | Question | Add `503 STORAGE_UNAVAILABLE` to the contract instead of today's `500`? | Contract change | The client and the team, here |
| Q-3 | Question | #341, #344 and #345 expect `415` for a wrong type, but the API answers `422` and the contract has no `415` | The tests of #345 would assert a code the API never sends | Keep `422` and correct those issues, or decide a `415` |
| Q-4 | Question | Strip the EXIF metadata on the server before storing? | Privacy of the citizen's position | The team; a separate issue if yes |
| Q-5 | Question | What is the Admin API rate limit on the Free plan, which the cleanup uses to list files? | The cleanup may need paging over several runs | Check in #344 |
| Q-6 | Question | Do Cloudinary's processor terms (DPA) cover the Free plan? | GDPR basis | Accepted as R-1 while the data is test data |
| Q-7 | Question | Check on the server what Cloudinary will refuse, before uploading: the pixel count (25 MP), and whether its 10 MB is 10,000,000 bytes while `EVIDENCE_MAX_FILE_SIZE=10MB` is 10,485,760? | A `422` after a provider call instead of before, for files between both limits | Decide in #344 |
| Q-8 | Question | Is close to 50 s of waiting acceptable before the app says that a photo failed, when the provider does not answer (7.4)? | The citizen's experience during an outage | The client, here; otherwise a shorter upload timeout |

## 12. Review and result

- **Review comments:** pending; two team members review first.
- **Resolution:** pending: to be presented to the client in class. Record here the date, the
  comments and the result.
- **Recorded decisions:** ADR-0027 (#343), once accepted.
- **Differences observed after implementing:** none yet.

## Approval checklist

- [ ] Problem and scope delimited.
- [ ] Drivers and constraints documented, without attributing to the assignment requirements it
      does not have.
- [ ] At least two alternatives compared, or a reason why there are no viable alternatives.
- [ ] Diagrams, data and contracts consistent.
- [ ] Failures, security, operation and costs considered in proportion to the change.
- [ ] Verifiable acceptance criteria and a reproducible test plan.
- [ ] Risks and open questions explicit.
- [ ] Technical review done; ADRs linked where they apply.

## Sources

Checked on 2026-11-21. Prices and limits change; ADR-0027 checks them again.

- Cloudinary:
  - [Pricing](https://cloudinary.com/pricing)
  - [plan comparison](https://cloudinary.com/pricing/compare)
  - [FAQ](https://cloudinary.com/faq)
  - [access control](https://cloudinary.com/documentation/control_access_to_media)
  - [upload API](https://cloudinary.com/documentation/image_upload_api_reference_upload)
  - [Java SDK](https://cloudinary.com/documentation/java_integration)
  - [terms](https://cloudinary.com/tou)
  - [privacy](https://cloudinary.com/privacy)
  - [sub-processors](https://cloudinary.com/trust/subprocessors)
- Cloudflare R2:
  - [pricing](https://developers.cloudflare.com/r2/pricing/)
  - [data location](https://developers.cloudflare.com/r2/reference/data-location/)
  - [presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)
  - [API tokens](https://developers.cloudflare.com/r2/api/tokens/)
  - [AWS SDK for Java](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/)
  - [terms](https://www.cloudflare.com/terms/)
  - [DPA](https://www.cloudflare.com/cloudflare-customer-dpa/)
- Backblaze B2:
  - [pricing](https://www.backblaze.com/cloud-storage/pricing)
  - [regions](https://www.backblaze.com/docs/cloud-storage-data-regions)
  - [application keys](https://www.backblaze.com/docs/cloud-storage-application-keys)
- AWS:
  - [Free Tier](https://aws.amazon.com/free/)
  - [Free Tier terms](https://aws.amazon.com/free/terms/)
  - [S3 presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
  - [data privacy FAQ](https://aws.amazon.com/compliance/data-privacy-faq/)
- [MinIO repository](https://github.com/minio/minio) (archived).
