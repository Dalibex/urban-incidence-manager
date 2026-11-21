# 📊 Datasets and Measurements

The sample data UrbanPulse loads for the demo and for the measurements, and the measurements taken
with it.

> [!NOTE]
> **For** anyone who gives the demo or measures performance. The development dataset is in the
> [development guide](development.md#-development-dataset); how every data profile is loaded,
> reloaded and changed, in *Data Profiles* in the [database guide](database.md#-data-profiles).

| Profile | Purpose | Where |
| --- | --- | --- |
| `dev` | Try the API and the clients while developing | [Development guide](development.md#-development-dataset) |
| `demo` | Show the system working, with sample data that simulates real use | *Demo Dataset* below and the [demo script](demo.md) |
| `perf` | Measure the quality attributes on a large, repeatable volume | *Performance Dataset* below |

## 📑 Contents

- **[Demo Dataset](#-demo-dataset)**: the sample data of the demo, and how it is loaded.
- **[Performance Dataset](#-performance-dataset)**: the large dataset for the measurements: what it generates and how to check it.
- **[Measurements](#-measurements)**: the query plans of the indexes, the load test of QA-1, the quality-attribute experiments and the metrics of the weather enrichment.

## 🎬 Demo Dataset

The `demo` Spring profile loads the sample data of the demo (#54): one user per role and fifteen
incidents in Málaga covering every status, each with a history allowed by
[ADR-0020](adr/0020-incident-lifecycle-transitions.md). The [demo script](demo.md) lists its users
and incidents and how to walk through them. It is separate from the development dataset: the
development data keeps the cases developers need (several citizens and technicians, an urban context
of each status, assets, notifications); the demo data is small and easy to follow in front of an
audience, and stores the weather and the address of its incidents as the workers
([ADR-0018](adr/0018-weather-and-traffic-providers.md), #375) would, so its incidents need no call
to Open-Meteo or Nominatim.

It follows the pattern of every data profile (*Data Profiles* in the
[database guide](database.md)):
`application-demo.yaml` adds `classpath:db/demo-data` to the Flyway locations,
`db/demo-data/R__demo_data.sql` is a repeatable migration, and `DemoDatasetIT` loads it on its own
PostGIS container.

- Activate it on an empty database, as *Start the Demo* in the [demo script](demo.md) explains: on
  the deployment, or in development like the `dev` profile, with `demo` instead of `dev`.
- Every demo user signs in with the password in the `DEMO_PASSWORD` environment variable (the
  `.env` at the repository root for the deployment, `backend/.env` in development). The backend
  does not start with the `demo` profile without it. Flyway passes it to the script as the `demo_password` placeholder and
  PostgreSQL hashes it with bcrypt (the `pgcrypto` extension, which the script enables), so no
  password or hash is in the repository. To change the password, reset the database and start
  again with the new value.
- Restarting keeps what was created during the demo; only a change to `R__demo_data.sql` or a
  reset inserts the demo data again.
- Each demo incident has its weather (`urban_context` `COMPLETE`, with its three observations): the
  real values of Open-Meteo for its position and the 15-minute step of its report, taken once
  from Open-Meteo's historical data, so the demo needs no call to Open-Meteo. The data is licensed
  CC BY 4.0, and wherever the weather is shown it says "Weather data by Open-Meteo.com"
  ([ADR-0018](adr/0018-weather-and-traffic-providers.md)). A report made during the demo gets its
  weather from Open-Meteo, which needs internet.
- Each demo incident with an address has it `AVAILABLE`, from `OpenStreetMap`, stored 30 seconds
  after its report; the first one has none and shows *Not available*. A report made during the
  demo gets its address from Nominatim some seconds later, which needs internet too
  ([ADR-0002](adr/0002-open-street-map.md)).

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
| `location` | 100,000 | Clustered around twelve places of Málaga, the city centre the busiest. About 8% without address, neighbourhood and district (their address `UNAVAILABLE`), and 5% without accuracy ("not available"). The rest have their address `AVAILABLE`, stored 30 seconds after the report, so the address worker has nothing pending. |
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

Three kinds are recorded below: the query plans that justify each index, the load test of QA-1, and
the quality-attribute experiments. The last two sections say which metrics measure the weather and
the address enrichment, which have no measurement yet.

### Query plans of the indexes

Each index of the [database guide](database.md#-indexes) that serves a query has its measurement
here: the query, its plan and time before and after the index, and what the measurement decided.

#### Main Queries

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

`V12__incidents_index_assignment_incident.sql` (#184) adds one more, measured in the same way on
2026-11-05, on commit `e29d3d6` with the perf dataset loaded by `0.1.0`:

| Query | Index | Before | After |
| --- | --- | --- | --- |
| Assignments of one incident: the active one of the staff detail, and the one a reassignment deactivates | `ix_assignment_incident_id` | 4.4 ms, seq scan of `assignment` | 0.06 ms, index scan |

It takes 2.5 MB with 80,000 assignments.

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

To repeat a measurement, load the `perf` dataset, open `psql` (*Determinism* above) and run the
query with `EXPLAIN (ANALYZE, BUFFERS)`, for example:

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
table here and the index in a new migration ([database guide](database.md)).

#### Staff Filters

`V11__incidents_index_staff_filters.sql` (#182) adds the indexes the filters of the incident list
need. The queries are those of the operator, who sees every incident, so nothing else narrows them.

| Query | Index | Before | After |
| --- | --- | --- | --- |
| Department with 7,058 active assignments, page of 20 | `ix_assignment_active_department` | 22.6 ms, scans of `ix_assignment_active_technician` for each incident | 1.7 ms, index-only scan |
| Department with 30,997 active assignments, page of 20 | `ix_assignment_active_department` | 4.4 ms, the same scans | 0.6 ms, index-only scan |
| Every incident sorted by `updatedAt`, page of 20 | `ix_incident_updated_at` | 10.9 ms, seq scan of `incident` and a sort | 0.1 ms, index scan |
| `REPORTED` sorted by `updatedAt`, page of 20 | `ix_incident_updated_at` | 5.0 ms, bitmap scan and a sort | 1.2 ms, index scan |

Conditions: the same data and method as *Main Queries* above, on PostgreSQL 18.6 with PostGIS 3.6 in
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

#### Classification Changes

`V14__identity_index_audit_log_target.sql` (#47) adds `ix_audit_log_target_id`, for the
classification changes of one incident (`listIncidentClassificationChanges`), which reads its
`CLASSIFY_INCIDENT` entries of `audit_log` by `target_id`:

| Query | Index | Before | After |
| --- | --- | --- | --- |
| Classification changes of one incident, page of 20 | `ix_audit_log_target_id` | 9.7 ms, parallel seq scan of `audit_log` | 0.1 ms, index scan |
| Its count | `ix_audit_log_target_id` | 8.3 ms, parallel seq scan | 0.03 ms, index scan |

The `perf` dataset has no audit entries, so for this measurement two `CLASSIFY_INCIDENT` entries
were added to each of its 100,000 incidents (200,000 entries) with an `INSERT ... SELECT` over
`incident`, followed by `VACUUM ANALYZE audit_log`. Measured once each with the cache warm, on
PostgreSQL 18.6 with PostGIS 3.6 in Docker on Linux (4 CPUs, 15 GB), on commit `284142a` plus this
change, on 2026-11-05. The index takes 4.9 MB.

#### Status and Priority

`V16__incidents_index_status_priority.sql` (#176) adds `ix_incident_status_priority_reported_at`
(`status, priority, reported_at DESC, id`), for the staff lists filtered by status and priority. The
page of 20 already took 7 ms through `ix_incident_reported_at`; its count did not:

| Query | Index | Before | After |
| --- | --- | --- | --- |
| Count of `REPORTED` and `VALIDATED` with priority `HIGH` (8,075 of 1,000,000) | `ix_incident_status_priority_reported_at` | 254 ms, bitmap scan of `ix_incident_status_reported_at` that reads 106,232 incidents and drops 98,157 of other priorities | 3.7 ms, index-only scan |

Under load, with 10 virtual users, the filtered inbox went from a p95 of 741 ms and 22 requests per
second to 16 ms and 827 requests per second (experiment 4 below).

Conditions: `perf` profile with `PERF_INCIDENTS=1000000`, `EXPLAIN (ANALYZE)` run once after
`VACUUM ANALYZE` with the cache warm, on PostgreSQL 18.6 with PostGIS 3.6 in Docker Desktop (12 CPUs,
8 GB) on a developer laptop, on commit `b618646` plus this change, on 2026-11-13. With the default
100,000 incidents the same filtered page took 28 ms at p95 without the index, so the index matters
from about a million incidents on.

### Quality-attribute experiments (#176)

The four experiments of the quality-attribute practice, run with k6 and seen in Grafana next to the
backend's metrics (the `observability` profile, [ADR-0023](adr/0023-load-test-observability.md)).
The scripts, how to run them, every table and the analysis are in
[`backend/load-tests/practice`](../backend/load-tests/practice/README.md) and its
[RESULTS.md](../backend/load-tests/practice/RESULTS.md). Each configuration ran three times; the
conditions are those of *QA-1* below (same laptop, Docker Desktop 12 CPUs and 8 GB, k6 1.0 on the
same machine), on commit `b618646` plus that branch, on 2026-11-13.

| Experiment | Load | Result |
| --- | --- | --- |
| 1. Slowest operations | 10 VUs per operation, 1 min, 100,000 incidents | The report (p95 309 ms), the dashboard (305 ms) and the map (231 ms) are the slowest; the detail and the history about 10 ms. All meet QA-1 |
| 2. Capacity | `ramping-vus` 10→100, `ramping-arrival-rate` and `constant-arrival-rate` of the staff mix | About 120 requests per second within QA-1; saturation at 125–140 per second (p95 3.7 s, no errors). The 10 database connections are all busy with about 190 requests waiting while the backend uses 8 % of CPU: the limit is PostgreSQL |
| 3. Emergency spike | Reports from 2 to 60 per second for a minute, with and without 40 users on the map | No errors; the users of the map triple the p95 of the reports (13 → 38 ms) |
| 4. Volume of data | The same queries with 1,000 to 1,000,000 incidents | The aggregates grow with the data; with 1,000,000 they failed with `500` until PostgreSQL got 256 MB of shared memory (`shm_size`); the index above |

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

### Weather enrichment (ADR-0018)

Not measured yet. The evaluation of [ADR-0018](adr/0018-weather-and-traffic-providers.md#how-it-will-be-evaluated)
reads these metrics of the backend at `/actuator/prometheus`
([ADR-0023](adr/0023-load-test-observability.md)):

- `urbanpulse_weather_requests_seconds`, by `outcome` (`answered`, `unavailable`, `rejected`): the
  calls to Open-Meteo and their time, for the calls per minute, hour and day.
- `urbanpulse_weather_contexts_total`, by `status` (`COMPLETE`, `ERROR`, `UNAVAILABLE`): the contexts
  the worker ended, for the share that is `COMPLETE`.
- `urbanpulse_weather_delay_seconds`: the time from the report to the stored weather, for the 60
  seconds of the threshold.

A load test must never call the real Open-Meteo: `run.sh` starts the backend with
`WEATHER_ENRICHMENT_ENABLED=false` ([load tests](../backend/load-tests/practice/README.md)).
