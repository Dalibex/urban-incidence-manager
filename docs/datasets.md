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

The query plans that justify the indexes of the main queries are in *Indexes* in the
[database guide](database.md).

### Load test of QA-1

`backend/load-tests/qa1-incidents.js` is the HTTP part of QA-1 ([architecture](architecture.md)): a
[k6](https://grafana.com/docs/k6/) script. It signs in as `perf.citizen1`, `perf.operator1`,
`perf.admin1` and `perf.technician1`, and keeps the incidents of each one's first page, so each role
only opens incidents it may read. The analyst is left out because `GET /incidents` answers `403`
to it. Then 5 virtual users per role (20 in all) repeat for 5 minutes:

1. open the first page of their list (`GET /incidents?page=0&size=20`);
2. wait 1 second;
3. open the detail and the history of one of those incidents;
4. wait 1 second.

The run fails when an operation of a role has a p95 of 500 ms or more, or when any request fails.

Run it against the backend in Docker with the `perf` dataset loaded. First start the stack on an
empty database, from `backend/`, with `SPRING_PROFILES_ACTIVE=perf` in `.env` (*Activate it*
above):

```bash
docker compose --profile app up --build -d
```

Then run k6 from its image, in the network of the stack. Nothing needs to be installed:

```bash
docker run --rm -i --network urbanpulse_default -e BASE_URL=http://backend:8080/api/v1 grafana/k6:1.0.0 run - < load-tests/qa1-incidents.js
```

`DURATION`, `VUS_PER_ROLE`, `THINK_TIME` (seconds) and `P95_MS` change the load and the target the
same way, with `-e`. Warm up first with one full run that is not recorded: after a one-minute
warm-up, the next run was still up to twice as slow in the operations of a few milliseconds (run 1
below). Each record keeps every field listed above.

The k6 summary prints p50 (`med`), p95, p99 and the maximum of each operation and role. A failed
target makes k6 exit with code `99` and name the crossed threshold, for example:

```text
✗ 'p(95)<500' p(95)=...
level=error msg="thresholds on metrics 'http_req_duration{role:operator,operation:list}' have been crossed"
```

#### QA-1, 2026-11-03 (#155)

| Field | Value |
| --- | --- |
| Operation | The script above: the list, the detail and the history, as citizen, operator, administrator and technician |
| Data | `perf` profile, 100,000 incidents (486,871 status changes, 5,055 users) |
| Load | 20 virtual users (5 per role) with 1 s between screens, for 5 minutes: about 30 requests per second, 8,903 requests per run. A one-minute warm-up and run 1 were not recorded |
| Environment | Developer laptop, not the demo machine: AMD Ryzen 5 4600H, 15.4 GB, Windows 11, Docker Desktop 29.8.2 (12 CPUs, 7.4 GB). Backend image and PostgreSQL 18.6 with PostGIS 3.6 in the Compose stack, k6 1.0.0 in a container on the same machine |
| Date and commit | 2026-11-03, runs at 13:40 and 13:46 UTC, `d3152c4` |

Result, in milliseconds. No request failed in either run.

| Role | Operation | p50 | p95 | p99 | p95, second run |
| --- | --- | --- | --- | --- | --- |
| Citizen | List | 6.0 | 8.1 | 12.0 | 7.2 |
| Citizen | Detail | 3.8 | 4.9 | 8.4 | 4.4 |
| Citizen | History | 4.5 | 6.2 | 12.2 | 5.2 |
| Operator | List | 16.9 | 25.2 | 34.2 | 24.3 |
| Operator | Detail | 3.6 | 4.8 | 6.5 | 4.4 |
| Operator | History | 4.3 | 5.9 | 7.8 | 5.3 |
| Administrator | List | 17.2 | 24.1 | 35.4 | 24.2 |
| Administrator | Detail | 3.5 | 4.9 | 6.8 | 4.3 |
| Administrator | History | 4.3 | 5.9 | 7.0 | 5.2 |
| Technician | List | 18.3 | 27.5 | 42.1 | 27.3 |
| Technician | Detail | 3.6 | 4.6 | 5.5 | 4.4 |
| Technician | History | 4.4 | 5.9 | 8.6 | 5.4 |

Every p95 is far under 500 ms; the slowest is the technician's list, at 28 ms. The two runs are within
20 % of each other in every operation. Run 1, right after the one-minute warm-up, was slower: up to
14 ms in the citizen's list and 9 ms in the detail.
