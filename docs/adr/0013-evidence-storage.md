# ADR-0013: Local volume behind a StorageService interface for evidences

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-07

## Context

The product lets citizens attach photographs and other files as evidences, and the domain requires
that `Attachment` keeps only metadata while the binary lives **outside the relational database**.
The security requirements add size and type validation, access control per role and
per-evidence, and a ban on user-supplied paths.

The deployment decision ([ADR-0011](0011-deployment-12factor.md)) runs everything with
`docker-compose` on one machine, and the risk analysis keeps infrastructure cost at zero. None of the previous ADRs decides *which* storage provider backs the evidence binaries.

Which storage should store evidence files in this phase?

## Decision

We will store evidence files in a **named Docker volume mounted in the backend container**, always
accessed through an application-level **`StorageService` interface**.

- **Operator contract:** the domain code only knows `StorageService` with methods such as
  `put(file, metadata, identity)`, `get(key)`, `delete(key)` and `exists(key)`. Neither Spring MVC
  controllers nor services depend on the concrete provider.
- **Implementation now:** a `LocalFsStorage` writes under the path configured in
  `STORAGE_BASE_PATH` (default `/app/storage`), backed by the named volume `evidence_data`
  declared in both `docker-compose` files.
- **Object keys are generated server-side** (UUID), never built from user input or URLs; the file
  name can never be attacker-chosen.
- **Metadata in PostgreSQL:** table rows (or attachments) keep size, detected content type,
  hash and the storage key; the binary is resolved through the interface on read.
- **Access control stays in the application:** a storage key is not a public URL; reading serves
  the file only after authorization checks per role and per evidence (AGENTS.md section 8).
- **Evolution path:** the next provider (S3-compatible, e.g. MinIO locally) is a new
  implementation of the same interface, adopted only when there is an observable problem, a
  testable hypothesis and a new ADR (AGENTS.md section 4).

### Evidence Policy

- **Accepted types:** JPEG, PNG and WebP, detected from the file content (magic bytes), not from the extension or the declared Content-Type.
- **Limits:** Maximum size per file from configuration (`EVIDENCE_MAX_FILE_SIZE`, default 10 MB); maximum files per incident from configuration (`EVIDENCE_MAX_FILES_PER_INCIDENT`, default 5).
- **Migration strategy:** A database migration (V9) replaces `attachment.storage_url` with `storage_key` (unique) and adds `content_hash` (SHA-256). Merged migrations are never edited (ADR-0014).
- **Security:** Files are served only through an authorised endpoint; a storage key is never a public URL.

## How it will be evaluated

- **Metric:** $p_{95}$ upload time of a 5 MB photo from a phone on the demo LAN.
- **Threshold:** Under 3 s.
- **Review Trigger:** When a second backend replica is required (triggers architectural review for S3-compatible object storage).

## Considered Options

- **Bytea columns in PostgreSQL** — No extra storage, but large binaries bloat the relational
  database, backups and cache, defeating the requirement that binaries live outside it.
- **Local filesystem without abstraction** — The simplest that satisfies the storage requirement,
  but it locks the application to the local machine and makes the promised cloud migration a
  refactor instead of a provider swap.
- **Local named volume behind an interface** — Selected. Satisfies the storage requirement today
  at zero cost, keeps the domain independent of the provider, and preserves the option to move to
  object storage without touching business code.
- **MinIO or another S3-compatible service from day one** — Realistic cloud shape, but adds a
  second stateful service to run, size and back up in a semester whose risk is scope, not
  infrastructure; it fails the "no component without an observable problem" rule.

## Consequences

- **Positives:**
  - Evidence files live outside the relational database, as required, and survive backend
    restarts.
  - The `StorageService` boundary keeps provider decisions out of the domain.
  - No new stateful service is introduced against [ADR-0011](0011-deployment-12factor.md).
  - The named volume is bounded and inspectable for a class demo.
- **Negatives:**
  - The volume lives on one machine: it must be included in the backup routine (the `db-backup`
    helper covers the database; evidence backups are documented in the Docker guide).
  - Not safe for multiple replicas (no shared storage); acceptable while the app is a single
    container, and the trigger to introduce S3-based storage.
  - File deletion is not automatic; orphaned binaries need a periodic sweep job (a real
    background task for the `worker` container of ADR-0011).

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
