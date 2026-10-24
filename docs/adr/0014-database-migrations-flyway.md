# ADR-0014: Versioned Database Migrations with Flyway

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-21

## Context

UrbanPulse uses a single PostgreSQL/PostGIS database owned by the backend application
([ADR-0010](0010-modular-monolith.md)), deployed with Docker Compose in every environment
(ADR-0011). Until now nothing decides how the database schema is created and evolved.

Without a decision, the schema ends up being changed in one of two ways:

- **By hand**, running `CREATE TABLE` / `ALTER TABLE` from pgAdmin or `psql` against each
  environment. Changes are not versioned, cannot be reviewed in a PR and are easily forgotten
  in one environment.
- **By Hibernate**, with `spring.jpa.hibernate.ddl-auto=update`. It cannot rename or drop
  columns, cannot migrate data, knows nothing about PostGIS-specific objects, and the resulting
  schema depends on the order in which entities changed in each environment.

Both lead to schema drift between the developer machines, Testcontainers, CI and the deployed
environments, which breaks dev/prod parity (Twelve-Factor X) and turns schema changes into
admin tasks done outside the codebase (Twelve-Factor XII).

**Question:** How should the database schema be created, versioned and applied so that every
environment has exactly the same schema, built from the code in the repository?

## Decision

We will manage every schema change with **Flyway**, using plain SQL migrations versioned in the
backend repository.

- **Dependencies.** `spring-boot-starter-flyway` and `flyway-database-postgresql` (Spring Boot 4
  needs the starter; `flyway-core` alone no longer triggers auto-configuration), plus
  `spring-boot-starter-flyway-test` for tests.
- **Location and naming.** Migrations live in `src/main/resources/db/migration` and follow
  `V<n>__<module>_<description>.sql` (e.g. `V3__incidents_create_incident_table.sql`). The module
  prefix keeps table ownership visible, as each module owns its tables
  ([ADR-0010](0010-modular-monolith.md)).
- **Plain SQL.** Migrations are written in SQL, so PostGIS types, spatial indexes and constraints
  are expressed directly ([ADR-0003](0003-geospatial-data-management.md)).
- **Immutability.** A migration that has been merged into `main` is never edited. Any further
  change, including fixes, is a new version. Flyway's checksum validation enforces this.
- **Hibernate only validates.** `spring.jpa.hibernate.ddl-auto=validate` in every environment:
  the application fails to start if the JPA entities and the schema disagree.
- **Configuration from the environment.** The connection comes from the standard Spring Boot
  variables (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`),
  as in the CI smoke test; in local development Spring Boot Docker Compose support provides it.
  No credentials in the code or in `application.yaml`.
- **When migrations run.** By default Flyway migrates on application startup, with the same
  image and configuration as the app. Migrations can also be run as a one-off admin process
  (the Flyway Maven plugin, which would have to be added to `pom.xml`, or a dedicated migration
  profile) for environments where migrating on
  startup is not wanted. Concurrent startups (e.g. `docker compose up --scale backend=2`) are
  safe because Flyway takes a database lock while migrating.
- **Tests.** Integration tests run against the same PostGIS image as Compose through
  Testcontainers, with the schema created only by Flyway.

**Drivers:**

- Same schema in every environment, built from the repository (Twelve-Factor X and XII).
- Schema changes reviewable in PRs, next to the code that needs them.
- Support for PostGIS-specific objects and data migrations.
- Low complexity for a student team: plain SQL, convention over configuration, first-class
  support in Spring Boot.

**Hypothesis and evaluation:**

- If all schema changes go through Flyway, no environment will show schema drift and every
  mismatch between entities and schema will be caught in CI, not in a deployed environment.
- Metric: schema-related failures found after merge; manual schema changes outside Flyway.
- Threshold: zero manual changes; the CI `test` job (integration tests) and `build` job
  (smoke test) catch every entity/schema mismatch before merge.
- Review: Sprint 2 retrospective. Revisit (e.g. towards Liquibase) if the team needs rollbacks
  or database-agnostic changesets.

## Considered Options

- **Flyway with SQL migrations** — Simple, convention-based, plain SQL, auto-configured by
  Spring Boot, checksum validation. Selected.
- **Liquibase** — Supports rollbacks and database-agnostic changesets (XML/YAML/SQL), but adds a
  changelog format to learn and more configuration. We only target PostgreSQL, so portability
  brings nothing.
- **Hibernate `ddl-auto=update`** — Zero effort at first, but unsafe: no renames, drops or data
  migrations, no history, no review, and non-deterministic results across environments.
- **Manual SQL scripts / `docker-entrypoint-initdb.d`** — Versioned, but init scripts only run
  on an empty volume and there is no record of which changes were applied where.
- **Keep the current situation** — No decision means drift is guaranteed as soon as the first
  entities (UP-129) arrive.

Flyway gives the versioning and validation we need with the least extra tooling. Liquibase's
extra features (rollbacks, portability) do not address any problem we have today.

## Consequences

- **Positives:**
  - The schema is reproducible from the repository in any environment, including a fresh
    database.
  - Schema changes are reviewed in the same PR as the entities and code that need them.
  - `flyway_schema_history` shows exactly which versions each database has.
  - Entity/schema mismatches fail fast at startup (`validate`) and in CI.
  - Full control over PostGIS types, indexes and constraints.
- **Negatives:**
  - Every entity change also needs a hand-written migration.
  - No automatic rollback in Flyway Community: undoing a change means writing a new forward
    migration, and destructive changes need care (expand/contract).
  - Two PRs created in parallel can claim the same version number; the second one must renumber
    its migration before merging (CI fails on the duplicate).
  - Migrating on startup makes startup slower when there are pending migrations, and a failed
    migration stops the application from starting.

## Links Related

- Issue: [12F-XII]: Admin processes — versioned database migrations with Flyway
- [ADR-0001](0001-database.md) (PostgreSQL)
- [ADR-0003](0003-geospatial-data-management.md) (geospatial data management)
- [ADR-0008](0008-backend.md) (backend)
- [ADR-0010](0010-modular-monolith.md) (modular monolith)
- [ADR-0011](0011-deployment-12factor.md) (deployment with Docker Compose)
- [ADR-0015](0015-ci-pipeline-and-pre-commit-formatting.md) (CI pipeline)
- Flyway documentation: https://documentation.red-gate.com/flyway
