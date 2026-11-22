# 🐘 Database Guide

How the PostgreSQL schema, with PostGIS, is created and changed, and how the data that users do not
create gets into it ([ADR-0001](adr/0001-database.md), [ADR-0003](adr/0003-geospatial-data-management.md)).

> [!NOTE]
> **For** anyone who changes the schema, an index or the sample data. To start, stop or reset the
> database, see the [deployment guide](deployment.md); the measurements behind the indexes are in
> the [datasets guide](datasets.md#query-plans-of-the-indexes).

## 📑 Contents

- **[Schema](#-schema)**: the diagram of every table.
- **[Migrations](#-migrations)**: how to write a migration, and the rules that protect them.
- **[Indexes](#-indexes)**: every index, its migration and the query it serves.
- **[Reference Data](#-reference-data)**: how the initial categories and departments are added and changed.
- **[Data Profiles](#-data-profiles)**: how the sample data of `dev`, `demo` and `perf` is loaded, reloaded and changed.

## 🗺 Schema

[![UrbanPulse database schema](database.drawio.svg)](database.drawio.svg)

The schema diagram above opens at full size with a click. It is a `.drawio.svg`: GitHub shows it as
an image, and draw.io opens it as an editable diagram. The migrations are the source of truth: if
the diagram and the migrations disagree, the migrations are right.

## 🧱 Migrations

The schema is created and changed only by Flyway
([ADR-0014](adr/0014-database-migrations-flyway.md)). Migrations are plain SQL files in
`backend/src/main/resources/db/migration` and run automatically when the backend starts, in every
environment and in the integration tests. Hibernate only validates the entities against the schema
(`ddl-auto: validate`): an entity that does not match its migration fails the `test` and `build` CI
jobs.

### Write a migration

- Name it `V<n>__<module>_<description>.sql`: the next free number, the module that owns the
  tables ([ADR-0010](adr/0010-modular-monolith.md)) and a description in lowercase with
  underscores, for example `V<n>__incidents_add_incident_source.sql`. Take the highest `V<n>` in
  `db/migration` plus one: a listing sorted by name puts `V9` after `V15`, so read the numbers.
- Write SQL keywords in uppercase and identifiers and types in lowercase (`varchar`, `uuid`,
  `timestamptz`), like the existing migrations.
- Update the [schema diagram](database.drawio.svg) in the same pull request.
- Add an index only with a measured query plan behind it, and write the measurement down (see
  *Indexes*).
- If the change affects a table that a dataset fills, update that dataset in the same pull request
  too (see *Data Profiles*).

### Rules

> [!WARNING]
> Never edit a migration that has been merged: write a new one, even for a fix. Flyway checks the
> checksum of every applied migration and refuses to start if one changed.

- If two branches use the same number, the second one to merge renumbers its migration.
- While a migration only exists on your branch, you can still change it: reset your local database
  ([deployment guide](deployment.md)) so Flyway applies it again from scratch.
- Rolling back an image does not roll back its migrations. A wrong migration is fixed with a new
  one that rolls forward.
- Two tables hold history, and triggers stop native SQL from rewriting it: `audit_log` refuses
  `UPDATE`, `DELETE` and `TRUNCATE` (V8), and `status_change` refuses `UPDATE` and `DELETE` (V17).
  `TRUNCATE` of `status_change` stays allowed, because the datasets empty it together with
  `incident` before they load it again. The integration tests remove the history they wrote with
  `TestHistories`, which turns the triggers off for its own transaction.

## 📇 Indexes

An index speeds up the reads that use it and slows down every write to its table, so an index is
added only when a query plan measured on the `perf` dataset shows that it changes the plan. The
measurement is written down, with its conditions, in *Query plans of the indexes* in the
[datasets guide](datasets.md#query-plans-of-the-indexes), and the index goes in a new migration.

PostgreSQL does not index foreign keys by itself. Besides the primary keys and the `UNIQUE`
constraints, the schema has these indexes:

| Index | Migration | What it serves |
| --- | --- | --- |
| `ix_location_coordinates` | V3 | Spatial queries on the incident position (PostGIS) |
| `ix_urban_asset_geometry` | V4 | Spatial queries on the urban assets (PostGIS) |
| `uq_refresh_token_token_hash`, `ix_refresh_token_user_id` | V6 | Finding a session by its token, and the sessions of a user |
| `ix_incident_reporter_reported_at` | V10 (#132) | *My reports* and its count |
| `ix_incident_reported_at` | V10 | The operator inbox, every status |
| `ix_incident_status_reported_at` | V10 | The operator inbox, one status |
| `ix_assignment_active_technician` | V10 | The technician's list and detail |
| `ix_status_change_incident_id` | V10 | The history of one incident |
| `ix_assignment_active_department` | V11 (#182) | The filter by department |
| `ix_incident_updated_at` | V11 | Sorting by `updatedAt` |
| `ix_assignment_incident_id` | V12 (#184) | The assignments of one incident |
| `uq_attachment_incident_content_hash` | V13 (#192) | Integrity, not speed: one photo per content and incident (ADR-0013). Two uploads at the same time are already serialised by a lock on the incident row (#275); the index is the database rule behind it. It needs no measurement |
| `ix_audit_log_target_id` | V14 (#47) | The classification changes of one incident |
| `ix_incident_status_priority_reported_at` | V16 (#176) | The count of the staff lists filtered by status and priority |
| `ix_location_address_pending` | V19 (#375) | The address worker: the next pending addresses and their expiry. Partial, only the `PENDING` locations |

`mfa_challenges` (V20, #57) has no index on `user_id`: it holds at most a day of sign-in challenges
of staff, because the daily clean-up deletes the expired ones, so reading it by user is a scan of a
few rows. Add one only with a measurement.

The indexes of V10 to V19, apart from the unique one, have their measurement in the
[datasets guide](datasets.md#query-plans-of-the-indexes).

## 📚 Reference Data

The initial categories and departments are inserted by a migration,
`V7__incidents_insert_reference_data.sql`, so every environment starts with the same rows. Never
insert them by hand or with a script run from a database client (Twelve-Factor XII).

- To add or change initial reference data, write a new migration with `INSERT` or `UPDATE`
  statements, for example `V<n>__incidents_add_graffiti_category.sql`. Do not edit V7.
- Give each row a fixed `id` (a UUID written in the migration), so the datasets and the tests can
  refer to it in every environment.
- To retire a category or a department, set `is_active = false` instead of deleting it: existing
  incidents and assignments still point to it.
- Add the new rows to the expected lists of `DatabaseMigrationIT`.
- Sample users and incidents are not reference data: only the data profiles load them (see below).

Once administrators can manage categories and departments from the application, a migration only
sets the starting point.

## 🧪 Data Profiles

A data profile loads sample data for one purpose: the `dev` profile for development
([development guide](development.md)), and the `demo` profile for the demo and the `perf` profile
for the quality attribute measurements ([datasets guide](datasets.md)). Production starts with no
profile and no sample data.

Every data profile follows the same pattern:

- `application-<profile>.yaml` adds `classpath:db/<profile>-data` to the Flyway locations.
- `db/<profile>-data/R__<profile>_data.sql` is a repeatable migration: Flyway runs it after the
  versioned migrations, and again whenever the file changes.
- An integration test (`DevelopmentDatasetIT`, `DemoDatasetIT`, `PerformanceDatasetIT`) loads it on
  its own PostGIS container and fails if it no longer fits the schema or the lifecycle rules
  ([ADR-0020](adr/0020-incident-lifecycle-transitions.md)).

### When the data reloads

- Restarting the backend keeps everything, including what you created yourself.
- When the dataset file changes (you edit it, or you pull a change to it), the next start runs it
  again: it deletes every incident and what belongs to it, yours too, and inserts the dataset
  again. The users of the dataset are updated in place by their fixed ids; users you signed up
  yourself stay.
- Each developer has their own database: what you create or delete never reaches your teammates.
- Restarting is not enough to undo your changes, because the file did not change. To start again
  from the dataset, reset the database and start with the profile.

### One profile at a time

> [!WARNING]
> Never activate two data profiles together: each one empties the incident data before inserting
> its own.

- A database that loaded a dataset does not start without its profile: Flyway finds that the
  repeatable migration was applied but is no longer among its migrations, and fails with
  *Migrations have failed validation*. To leave a profile, or to switch to another, reset the
  database first.

### Change a dataset

- Any change to the file, even to a comment, reloads the dataset on the next start of every
  teammate who uses that profile. Change it only when the data needs it.
- Give every new row a fixed id that follows the scheme described at the top of its file.
- Never delete a user of a dataset: set `enabled = false`, because other records may still point to
  it.
- Do not write `${` in the file: Flyway would read it as a placeholder. Only the placeholders the
  profile defines on purpose, such as `${demo_password}` or `${perf_incidents}`, may appear.
