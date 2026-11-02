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
  underscores, for example `V10__incidents_add_incident_source.sql`. `ls db/migration` shows the
  last number.
- Write SQL keywords in uppercase and identifiers and types in lowercase (`varchar`, `uuid`,
  `timestamptz`), like the existing migrations.
- Update the [schema diagram](database.drawio.svg) in the same pull request.
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
([development guide](development.md)) and the `demo` profile for the demo
([datasets guide](datasets.md)). Production starts with no profile and no sample data.

Every data profile follows the same pattern:

- `application-<profile>.yaml` adds `classpath:db/<profile>-data` to the Flyway locations.
- `db/<profile>-data/R__<profile>_data.sql` is a repeatable migration: Flyway runs it after the
  versioned migrations, and again whenever the file changes.
- An integration test (`DevelopmentDatasetIT`, `DemoDatasetIT`) loads it on its own PostGIS
  container and fails if it no longer fits the schema or the lifecycle rules
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

- Give every new row a fixed id that follows the scheme described at the top of its file.
- Never delete a user of a dataset: set `enabled = false`, because other records may still point to
  it.
- Do not write `${` in the file: Flyway would read it as a placeholder. Only the placeholders the
  profile defines on purpose, such as `${demo_password}`, may appear.
