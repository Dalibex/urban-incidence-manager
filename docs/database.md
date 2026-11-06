# 🗄️ Database Guide

How the PostgreSQL/PostGIS schema is created and changed, and how data that is not created by the
users gets into it. The database is PostgreSQL with PostGIS ([ADR-0001](adr/0001-database.md),
[ADR-0003](adr/0003-geospatial-data-management.md)); the [schema diagram](database.drawio.svg)
shows its tables. To start, stop or reset the database, see the
[deployment guide](deployment.md).

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
  underscores, for example `V10__incidents_add_incident_source.sql`. The last file in
  `db/migration` has the highest number in use.
- Write SQL keywords in uppercase and identifiers and types in lowercase (`varchar`, `uuid`,
  `timestamptz`), like the existing migrations.
- Update the [schema diagram](database.drawio.svg) in the same pull request.
- Add an index only with a measured query plan behind it, and write the measurement down (see
  *Indexes*).
- If the change affects a table that a dataset fills, update that dataset in the same pull request
  too (see *Data Profiles*).

### Rules

- Never edit a migration that has been merged: write a new one, even for a fix. Flyway checks the
  checksum of every applied migration and refuses to start if one changed.
- If two branches use the same number, the second one to merge renumbers its migration.
- While a migration only exists on your branch, you can still change it: reset your local database
  ([deployment guide](deployment.md)) so Flyway applies it again from scratch.
- Rolling back an image does not roll back its migrations. A wrong migration is fixed with a new
  one that rolls forward.

## 📇 Indexes

An index speeds up the reads that use it and slows down every write to its table, so an index is
added only when a query plan measured on the `perf` dataset ([datasets guide](datasets.md)) shows
that it changes the plan, and the measurement is written down here. PostgreSQL does not index
foreign keys by itself: the primary keys, the `UNIQUE` constraints and the indexes below are all
there is.

### Main Queries

`V10__incidents_index_main_queries.sql` (#132) adds one index for each main query of the incidents
module. The queries are the SQL Hibernate generates for the listing, the count of its page, the
detail and the history (`IncidentQueryService`), captured with `spring.jpa.show-sql`.

| Query | Index | Before | After |
| --- | --- | --- | --- |
| *My reports*: a citizen's page of 20, newest first | `ix_incident_reporter_reported_at` | 17.6 ms, seq scan of `incident` | 0.5 ms, index scan |
| *My reports*: its count | `ix_incident_reporter_reported_at` | 21.1 ms, seq scan | 1.6 ms, index-only scan |
| Operator inbox, every status, page of 20 | `ix_incident_reported_at` | 111.3 ms, seq scans of `incident` and `location` and a sort | 0.5 ms, index scan |
| Operator inbox, one status, page of 20 | `ix_incident_status_reported_at` | 15.5 ms, seq scan | 0.4 ms, index scan |
| Operator inbox, one status, count | `ix_incident_status_reported_at` | 18.1 ms, seq scan | 1.5 ms, index-only scan |
| Technician list, page of 20 | `ix_assignment_active_technician` (and `ix_incident_reported_at`) | 20.7 ms, seq scans of `incident` and `assignment` | 8.1 ms, index scans |
| Technician list, count | `ix_assignment_active_technician` | 11.0 ms, seq scan of `assignment` | 8.0 ms, index-only scan |
| Technician detail of one incident | `ix_assignment_active_technician` | 5.1 ms, seq scan of `assignment` | 0.1 ms, index-only scan |
| History of one incident | `ix_status_change_incident_id` | 25.3 ms, seq scan of `status_change` | 0.1 ms, bitmap index scan |

Conditions:

- Data: `perf` profile with the default volume, 100,000 incidents, about 487,000 history entries
  and 80,000 assignments. The citizen is `perf.citizen1@example.com`, the one with the most
  incidents (5,901); the technician has 2,089 active assignments; the status is `REPORTED` (6,512
  incidents); the incident of the history has 6 entries.
- Method: `EXPLAIN (ANALYZE, BUFFERS)` of each query, run six times after `VACUUM ANALYZE`; the
  table gives the median of the last five, with the cache warm. Execution time only, without the
  network or the backend.
- Environment: PostgreSQL 18.6 with PostGIS 3.6 (`postgis/postgis:18-3.6`) in Docker, 4 CPUs and
  16 GB of memory, on commit `2bd35be` plus this migration, on 2026-11-02.

What the measurements decided:

- The technician's queries gain the least: there is no index that gives both "assigned to me" and
  "newest first", so PostgreSQL walks the newest incidents and checks each one's assignment. With
  2,000 incidents per technician it stays under 10 ms.
- Filtering by several statuses at once uses `ix_incident_reported_at` and filters as it walks:
  0.5 ms for `REPORTED`, `REOPENED` and `VALIDATED`.
- The history index is on `incident_id` alone. The wider `(incident_id, instant, id)`, which also
  gives the order, measured the same time (0.06 ms against 0.08 ms) and was four times larger
  (27 MB against 6.6 MB), because PostgreSQL only deduplicates repeated keys.
- The indexes take 25 MB in total with this volume, against 33 MB of the `incident` table.

To repeat a measurement, load the `perf` dataset, open `psql` (*Determinism* in the
[datasets guide](datasets.md)) and run the query with `EXPLAIN (ANALYZE, BUFFERS)`, for example:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT i.id, i.title, i.status, i.reported_at, l.address
  FROM incident i
  LEFT JOIN category c ON c.id = i.category_id
  LEFT JOIN location l ON l.incident_id = i.id
 WHERE i.reporter_id = 'f0000000-0000-4000-8000-000000000001'
 ORDER BY i.reported_at DESC, i.id
 OFFSET 0 ROWS FETCH FIRST 20 ROWS ONLY;
```

When a new query or filter is added, measure it the same way and add its index, with its row in a
table here, in a new migration.

### Staff Filters

`V11__incidents_index_staff_filters.sql` (#182) adds the indexes the filters of the incident list
need. The queries are those of the operator, who sees every incident, so nothing else narrows them.

| Query | Index | Before | After |
| --- | --- | --- | --- |
| Department with 7,058 active assignments, page of 20 | `ix_assignment_active_department` | 22.6 ms, scans of `ix_assignment_active_technician` for each incident | 1.7 ms, index-only scan |
| Department with 30,997 active assignments, page of 20 | `ix_assignment_active_department` | 4.4 ms, the same scans | 0.6 ms, index-only scan |
| Every incident sorted by `updatedAt`, page of 20 | `ix_incident_updated_at` | 10.9 ms, seq scan of `incident` and a sort | 0.1 ms, index scan |
| `REPORTED` sorted by `updatedAt`, page of 20 | `ix_incident_updated_at` | 5.0 ms, bitmap scan and a sort | 1.2 ms, index scan |

Conditions: the same data and method as *Main Queries*, on PostgreSQL 18.6 with PostGIS 3.6 in
Docker Desktop (16 CPUs, 15.2 GB), on commit `284142a` plus this change, on 2026-11-05.

What the measurements decided:

- The category, priority and date filters need no index: their pages take under 1.1 ms with
  `ix_incident_reported_at`, which PostgreSQL walks newest first until it has 20 rows. A week of
  incidents also counts in 0.2 ms with it.
- Their counts read the whole `incident` table, about 13 ms for one category or one priority. An
  index per filter would only serve that count, so they are left as they are.
- The count of a department does not improve: 10 and 15 ms with the index, against 10 and 13 ms
  without it, because it still checks every incident. Only its page does.
- The two indexes take 8 MB in total with this volume.

### Integrity

A unique index that keeps a rule of the data is not a performance index, so it needs no
measurement:

| Index | Migration | Rule |
| --- | --- | --- |
| `uq_attachment_incident_content_hash` on `attachment (incident_id, content_hash)` | `V13__incidents_unique_attachment_content.sql` (#192) | One photo per content and incident: two uploads of the same file at the same time store it once (ADR-0013) |

## 📚 Reference Data

The initial categories and departments are inserted by a migration,
`V7__incidents_insert_reference_data.sql`, so every environment starts with the same rows. Never
insert them by hand or with a script run from a database client (Twelve-Factor XII).

- To add or change initial reference data, write a new migration with `INSERT` or `UPDATE`
  statements, for example `V10__incidents_add_graffiti_category.sql`. Do not edit V7.
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

- Never activate two data profiles together: each one empties the incident data before inserting
  its own.
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
