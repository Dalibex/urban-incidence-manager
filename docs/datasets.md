# 📊 Datasets and Measurements

The datasets UrbanPulse loads for a purpose other than development, and the measurements taken
with them. The development dataset (`dev` profile) is in the [development guide](development.md);
how every data profile is loaded, reloaded and changed is in *Data Profiles* in the
[database guide](database.md).

| Profile | Purpose | Where |
| --- | --- | --- |
| `dev` | Try the API and the clients while developing | [Development guide](development.md) |
| `demo` | Tell one story in front of an audience | *Demo Dataset* below and the [demo script](demo.md) |
| `perf` | Measure the quality attributes on a large, repeatable volume | *Performance Dataset* below |

## 🎬 Demo Dataset

The `demo` Spring profile loads the curated story of the demo (#54): one user per role and fifteen
incidents in Málaga covering every status, each with a history allowed by
[ADR-0020](adr/0020-incident-lifecycle-transitions.md). The [demo script](demo.md) lists its users
and incidents and how to walk through them. It is separate from the development dataset: the
development data keeps the cases developers need (several citizens and technicians, urban context,
assets, notifications), the demo data tells one story that is easy to follow.

It follows the pattern of every data profile (*Data Profiles* in the
[database guide](database.md)):
`application-demo.yaml` adds `classpath:db/demo-data` to the Flyway locations,
`db/demo-data/R__demo_data.sql` is a repeatable migration, and `DemoDatasetIT` loads it on its own
PostGIS container.

- Activate it as the `dev` profile (see *Activate it* in the
  [development guide](development.md)), with `demo` instead of `dev`, on an empty database. To show
  the mobile app on a phone, run the backend from the IDE or the terminal (see the
  [demo script](demo.md)).
- Every demo user signs in with the password in the `DEMO_PASSWORD` environment variable
  (`backend/.env`, or the environment of the container). The backend does not start with the
  `demo` profile without it. Flyway passes it to the script as the `demo_password` placeholder and
  PostgreSQL hashes it with bcrypt (the `pgcrypto` extension, which the script enables), so no
  password or hash is in the repository. To change the password, reset the database and start
  again with the new value.
- Restarting keeps what was created during the demo; only a change to `R__demo_data.sql` or a
  reset inserts the story again.

## 📈 Performance Dataset

The `perf` Spring profile loads a large dataset for the quality attribute measurements (#84), so
every team member measures against exactly the same rows instead of the ten incidents of `dev`,
where every query is fast. It is the *data* part of the measurement conditions: record the
operation, load and environment of each measurement as *Measurements* below describes.

`application-perf.yaml` adds `classpath:db/perf-data` to the Flyway locations, like the other data
profiles (*Data Profiles* in the [database guide](database.md)), but `db/perf-data/R__perf_data.sql`
does not store the rows: it generates them inside PostgreSQL with `generate_series`, so the script
stays small and the volume can change without rewriting it. `PerformanceDatasetIT` generates 400 incidents on its own PostGIS container to check
the script against the schema and the lifecycle rules; CI never loads the full volume.

### What it generates

With the default volume of 100,000 incidents:

| Table | Rows | Distribution |
| --- | --- | --- |
| `users` | 5,055 | 5,000 citizens (one per twenty incidents) and a fixed staff of 10 operators, 40 technicians, 2 administrators and 3 analysts. A few citizens report many incidents: the top tenth reports about half of them. |
| `incident` | 100,000 | Reported between November 2025 and mid-October 2026. Older incidents are mostly closed (about 59% closed, 10% rejected overall), recent ones still in progress. Categories in realistic proportions; a quarter of the unvalidated incidents have none. |
| `location` | 100,000 | Clustered around twelve places of Málaga, the city centre the busiest. About 8% without address, neighbourhood and district, and 5% without accuracy ("not available"). |
| `status_change` | about 487,000 | The history of each incident, only transitions of [ADR-0020](adr/0020-incident-lifecycle-transitions.md); 12% include an information request. |
| `assignment`, `comment`, `attachment`, `notification` | about 80,000, 30,000, 40,000 and 94,000 | One assignment per assigned incident, a comment on 30% of the incidents, attachment metadata on 40%, and a notification of the last change. |

Every user signs in with the password `urbanpulse-perf`, with emails such as
`perf.citizen1@example.com` (the most active citizen), `perf.operator1@example.com`,
`perf.technician1@example.com`, `perf.admin1@example.com` and `perf.analyst1@example.com`.

### Activate it

- Activate it as the `dev` profile (see *Activate it* in the [development guide](development.md)),
  with `perf` instead of `dev`, on an empty database: reset it first (*Reset* in the
  [deployment guide](deployment.md)). Never activate it together with `dev` or `demo`: each one
  empties the incident data of the others.
- Loading is a slow admin process, which is why it stays out of `dev`: the default volume took
  about 36 seconds of Flyway on a developer laptop (PostgreSQL 18 in Docker), and the whole start
  about a minute. Later starts do not load it again.
- The volume is the `PERF_INCIDENTS` environment variable (`backend/.env`, or the environment of the
  container), 100,000 when it is not set. Flyway passes it to the script as the `perf_incidents`
  placeholder. To change it, reset the database and start again with the new value.

### Determinism

The script fixes the random seed (`SELECT setseed(0.84)`) and draws every random value once per
incident, in order; ids come from row numbers and the password hash from a fixed salt. Two loads
with the same volume give exactly the same rows on any machine. To check it (T2 of #84), run this
query after each load and compare the results:

```sql
SELECT 'incident' AS rows, count(*) AS total, md5(string_agg(id::text || status
    || coalesce(priority, '') || coalesce(category_id::text, '')
    || extract(epoch FROM reported_at) || extract(epoch FROM updated_at), ',' ORDER BY id)) AS checksum
  FROM incident
UNION ALL
SELECT 'status_change', count(*), md5(string_agg(id::text || new_status || actor_id::text
    || extract(epoch FROM instant), ',' ORDER BY id))
  FROM status_change
UNION ALL
SELECT 'location', count(*), md5(string_agg(id::text || ST_AsText(coordinates::geometry)
    || coalesce(address, ''), ',' ORDER BY id))
  FROM location
UNION ALL
SELECT 'users', count(*), md5(string_agg(id::text || email || password_hash, ',' ORDER BY id))
  FROM users WHERE id::text LIKE 'f0000000-%'
ORDER BY 1;
```

From `backend/`, with the database running:
`docker compose exec postgres psql -U urbanpulse -d urbanpulse`, then paste the query.
`PerformanceDatasetIT` runs the same comparison on every build.

### Change the dataset

The rules of every dataset apply (*Change a dataset* in the [database guide](database.md)). On top
of them:

- When a migration changes a table the dataset fills, update `R__perf_data.sql` in the same pull
  request; `PerformanceDatasetIT` fails if it no longer fits the schema or the lifecycle rules.
- Draw new random values only as new columns of `perf_draw`, after the existing ones: a value drawn
  anywhere else, or in another order, changes every row that follows and the checksums with them.
- Apart from `${perf_incidents}`, do not write `${` in the file.

## 📏 Measurements

Results of the quality attribute measurements. A number without its conditions cannot be compared
or repeated, so every measurement records:

| Field | Example |
| --- | --- |
| Operation | `GET /incidents?page=0&size=20` as an operator |
| Data | Profile and volume |
| Load | Concurrent users or requests per second, and duration |
| Environment | Machine, Docker resources, versions of the backend and the database |
| Result | Percentiles (p50, p95, p99), never only the average |
| Date and commit | When and on which commit it was measured |

No measurements have been recorded yet.
