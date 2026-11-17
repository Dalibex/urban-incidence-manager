# ADR-0018: Use Open-Meteo for the weather of an incident, enriched after the report with no cache

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-11-17 (proposed on 2026-10-25 together with traffic)

## Context

The `urban_context` table (migration `V4__urbancontext`) stores the weather around an incident
in the enum column `weather` (SUNNY, WINDY, CLOUDY, PARTLY_CLOUDY, RAINY, STORMY, SNOWY, FOGGY),
and its `status` (PENDING, COMPLETE, PARTIAL, ERROR, UNAVAILABLE) says whether that context is
there. Every raw value must also be an `external_observation` with its source, observation
instant, ingestion instant, unit and quality, as [ADR-0009](0009-urban-data-source.md) requires.

The weather of each incident enters the MVP (#304: RF23 external context, RF24 contextual view,
RF26 controlled degradation). Four constraints shape the decision:

- **No source has the weather yet.** OpenStreetMap holds static map data and MapLibre only draws
  the map ([ADR-0016](0016-self-hosted-osm-base-map.md)). Nothing so far shows that Open Data
  Málaga ([ADR-0009](0009-urban-data-source.md)) publishes the current weather by coordinates.
- **The report never waits for it.** Enrichment runs after the incident is created and an
  outage never blocks a report ([ADR-0009](0009-urban-data-source.md), QA-2 in the
  [architecture overview](../architecture.md)). ADR-0009 does not say how it runs after the
  report, and modules do not talk through application events
  ([ADR-0010](0010-modular-monolith.md)).
- **No cache without a measurement.** [ADR-0022](0022-redis-from-measurements.md) replaced the
  Redis cache of [ADR-0012](0012-cache-redis.md): an external adapter alone does not add Redis.
- **Traffic is a separate decision.** The first version of this ADR also chose TomTom for the
  traffic level. TomTom needs an account, a key and its own allowance, and the MVP takes only the
  weather, so traffic moved to [ADR-0024](0024-traffic-provider.md) (*Proposed*). This file keeps
  its first name so that the links to it keep working.

Which source gives the weather of an incident, how does the backend fetch it after the report,
and how is its value turned into the enum of the schema?

## Decision

The `urbancontext` module ([ADR-0010](0010-modular-monolith.md)) gets the current weather from
**Open-Meteo**, through a scheduled worker that enriches the incidents whose context is
`PENDING`. There is no cache.

### Source: Open-Meteo

- **Request:** `GET /v1/forecast` with the incident coordinates and
  `current=weather_code,wind_speed_10m,precipitation`, in UTC. It returns the WMO weather code, the
  wind speed in km/h, the precipitation in mm and the instant they stand for. These are the current
  conditions of its weather models, in steps of 15 minutes, not readings of a station. Open-Meteo
  covers the whole world, so every valid position gets an answer. No API key and no account are
  needed.
- **Mapping to `weather`:**

  | WMO code | `weather` |
  |---|---|
  | 0 | SUNNY |
  | 1, 2 | PARTLY_CLOUDY |
  | 3 | CLOUDY |
  | 45, 48 | FOGGY |
  | 51–67, 80–82 | RAINY |
  | 71–77, 85, 86 | SNOWY |
  | 95–99 | STORMY |
  | 0–3, 45 or 48 with wind ≥ 40 km/h | WINDY, instead of the value above |

  A code outside the table, or an answer without a code, gives no `weather`: the context is
  `UNAVAILABLE`, never a guessed value. An answer with a code but no wind speed is mapped by the
  code alone, and no wind observation is stored.
- **Attribution:** Open-Meteo data is licensed under CC BY 4.0. Wherever the weather is shown,
  the screen shows "Weather data by Open-Meteo.com".
- **Usage terms:** the free API is for non-commercial use, under 10,000 calls a day, 5,000 an hour
  and 600 a minute.

### Mechanism: a worker over the pending contexts

1. **The report only writes a row.** In the transaction that creates the incident, the
   `incidents` module calls the `urbancontext` service directly (ADR-0010) to insert its
   `urban_context` with `status = PENDING`. Creating an incident calls no external source.
2. **A scheduled worker** in `urbancontext` runs every `WEATHER_ENRICHMENT_INTERVAL`. It first
   marks `UNAVAILABLE`, in one `UPDATE`, every `PENDING` context whose report is older than
   `WEATHER_MAX_DELAY`, so an old backlog never takes the place of new reports. Then it takes up
   to `WEATHER_ENRICHMENT_BATCH_SIZE` of the oldest `PENDING` contexts and, for each one, calls
   Open-Meteo with a timeout of `OPEN_METEO_TIMEOUT`, with no transaction open during the call.
   The adapter lives in the `client/` sub-package of the module (ADR-0010).
3. **The result is written only if the row is still `PENDING`**: one atomic
   `UPDATE … WHERE id = ? AND status = 'PENDING'`, in a short transaction that inserts the
   observations only if it changed one row. If another run wrote it first, the result is dropped.
   Together with `uq_urban_context_incident`, an incident has one context and is never enriched
   twice, even with two runs or two replicas. Two replicas would still call Open-Meteo twice for
   the same batch, which is one of the review points below.
4. **A pending context survives a restart:** it is still `PENDING` in the database, and the next
   run takes it.

### Outcomes

| What happens | `urban_context.status` | Retried |
|---|---|---|
| Open-Meteo answers with a code of the table | `COMPLETE`, with `weather` and `captured_at` | — |
| Timeout, no network, `429` or `5xx` | Stays `PENDING`, and the run goes on with the next context. A second failure in a row stops the run: the rest of the batch waits for the next run | Yes, on the next run |
| The report is older than `WEATHER_MAX_DELAY` | `UNAVAILABLE`, without calling Open-Meteo: the current weather no longer stands for the weather at the report | No |
| An answer without a WMO code, or a code outside the table | `UNAVAILABLE` | No |
| Any other `4xx` (Open-Meteo answers `400` to a position it cannot use), or a body the adapter cannot read | `ERROR`: the same request would fail again | No |

Retries are bounded by time, not by a counter: a context is retried on each run until
`WEATHER_MAX_DELAY` has passed since its report. One failure alone does not stop the run, so a
context whose call fails does not hold back the newer ones; two failures in a row mean that
Open-Meteo itself is failing, so an outage costs at most two calls per run. `PARTIAL` is not used
while the weather is the only value; it is for when traffic joins ([ADR-0024](0024-traffic-provider.md)).

The status says what kind of failure it was: `UNAVAILABLE` when there is no weather to give (too
late, or no code), `ERROR` when the request or the answer is wrong. The exact cause (the
timeout, the HTTP status) goes to the log; `urban_context` has no column for it.

### Provenance

- `captured_at` is the instant the backend stored the weather, so the time from the report to the
  weather is `captured_at − reported_at`.
- Each raw value is an `external_observation` with source `Open-Meteo` and status `VALID`:
  `WEATHER_CODE` (unit `WMO`), `WIND_SPEED` (`km/h`) and `PRECIPITATION` (`mm`). Its
  `observation_instant` is the instant Open-Meteo returns, and its `ingestion_date` the instant
  the backend stored it, kept apart.
- `quality` stays empty, because Open-Meteo gives none (unknown is not zero). `expiration_date`
  stays empty too: the value describes the weather at the report, which does not go out of date;
  what goes out of date is the *current* weather, and `WEATHER_MAX_DELAY` handles that.
- No observation is written for `UNAVAILABLE` or `ERROR`: there is no value to keep, and the
  status of the context already says so.

### Configuration

Environment variables with defaults ([ADR-0011](0011-deployment-12factor.md)). #307 adds them to
the [deployment guide](../deployment.md) and to `.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `WEATHER_ENRICHMENT_ENABLED` | `true` | Turns the worker off. Reports still create their `PENDING` context; when it is turned on again, the ones older than `WEATHER_MAX_DELAY` become `UNAVAILABLE` |
| `OPEN_METEO_BASE_URL` | `https://api.open-meteo.com` | Base URL, so the tests point to a fake server |
| `WEATHER_ENRICHMENT_INTERVAL` | `PT30S` | Time between two runs of the worker |
| `WEATHER_ENRICHMENT_BATCH_SIZE` | `20` | Contexts per run. It caps the rate of the adapter's own requests that ADR-0022 asks for: at most 40 calls a minute, under the 600 of Open-Meteo. The daily count depends on the reports, one call each |
| `OPEN_METEO_TIMEOUT` | `PT2S` | Connection and read timeout of each call |
| `WEATHER_MAX_DELAY` | `PT1H` | From this age of the report on, the current weather does not stand for the weather at the report |

`data_source_config` is not used for Open-Meteo: it is for the *Sources* screen of the
administrator, which is not in the MVP.

### No cache

Each incident calls Open-Meteo once and keeps its weather in PostgreSQL, so the same incident
never asks twice. Two reports at the same place call twice. The adapter measures its calls and
their latency, and [ADR-0022](0022-redis-from-measurements.md) decides from those measurements
whether a cache enters.

## Considered Options

**Source:**

- **Open-Meteo.** No key, current weather by coordinates, a simple JSON answer, and WMO codes
  that map directly to the enum. Selected.
- **AEMET OpenData** (Spain's national weather agency). Official and free, but it needs an API key
  and answers in two steps: one request returns a URL, and a second request returns the data. It
  gives forecasts per municipality or station, not the current weather at a point. It stays the
  fallback if Open-Meteo's terms become a problem.
- **OpenWeatherMap.** Current weather by point, but it needs an account and a key, and its free
  tier has a daily limit.
- **OpenStreetMap / MapLibre.** No weather data; listed only because the question was asked.
- **No weather.** The column would stay without data and the context view without its only value.

**Deferred mechanism:**

- **A worker over the `PENDING` rows.** It survives a restart, needs no event between modules
  and makes the retries explicit. Selected.
- **Another thread after the commit.** The weather arrives sooner, but a restart loses the pending
  work, and it needs a transaction hook or an application event (against ADR-0010).
- **A message broker.** A new component with no observable need (`AGENTS.md`, section 8).
- **Calling Open-Meteo while reporting.** A slow answer would delay or break the report (RF26).

**Retries:**

- **Bounded by time, the second failure in a row stops the run.** No schema change; a context
  that fails does not hold back the others, and an outage costs at most two calls per run.
  Selected.
- **Bounded by time, the first failure stops the run.** An outage costs one call per run, but a
  context whose call always fails, being the oldest, would hold back every newer report until it
  reaches `WEATHER_MAX_DELAY`.
- **A column `last_attempt_at`, with the queue ordered by the last attempt.** A context that fails
  goes to the end of the queue, so no context ever holds back another, with one call per run in
  an outage. It needs a migration with a new column in `urban_context`, for a case nothing has
  shown yet; it is the fix if the measurements show the risk below.
- **A counter of attempts with a growing wait.** More control per context, but it needs a
  migration with new columns in `urban_context`, for an outage the time bound already handles.

**Configuration:** environment variables, like every other setting of the backend (selected),
or a row of `data_source_config` seeded by a migration, which nobody can edit until the *Sources*
screen exists.

**Cache:** none, until a measurement asks for it (selected, ADR-0022), or Redis now, as the first
version of this ADR said, which adds a service with no measured need.

## Consequences

- **Positives:**
  - Each incident gets the weather of its report without depending on Open-Meteo to be created.
  - The pending work survives a restart, and no new component or secret is needed.
  - Every failure ends in an explicit status; unknown weather is never shown as a value.
  - Open-Meteo sits behind an adapter of `urbancontext`: changing to AEMET replaces that adapter
    and nothing else.
- **Negatives:**
  - The weather appears some seconds after the report, up to one interval plus the call.
  - The worker runs a small query every 30 seconds, even when nothing is pending.
  - With no cache, repeated reports at the same place call Open-Meteo each time.
  - After an outage longer than `WEATHER_MAX_DELAY`, the reports of that time have no weather.
  - With the defaults, the worker keeps up with 40 reports a minute; above that, a backlog builds
    and the weather arrives later.
  - In an outage the worker makes two calls per run instead of one: with the defaults, four a
    minute, about 5,800 in a whole day without Open-Meteo, under its 10,000 a day. The threshold
    below leaves those days out, because a cache would not save these calls: they retry the same
    contexts until Open-Meteo answers.
  - A context whose call always fails with a timeout or a `5xx` costs one wasted call per run
    until it reaches `WEATHER_MAX_DELAY`: 120 calls with the defaults.
  - The oldest pending contexts go first, so two contexts in a row at the head of the queue whose
    calls always fail would stop every run and hold back the newer reports until they reach
    `WEATHER_MAX_DELAY`. Nothing shows Open-Meteo failing like that for one point. If the
    measurements show it, the fix is the column `last_attempt_at` of *Considered Options*.
  - Open-Meteo's free API is for non-commercial use only. A commercial deployment needs a paid
    plan or AEMET.
  - Incidents reported before this enrichment have no `urban_context`; the API of #306 says how it
    shows that.

## How it will be evaluated

- **Metric:**
  - share of the incidents reported while Open-Meteo answers whose context is `COMPLETE`, and the
    time from `reported_at` to `captured_at`, on the demo machine with the `demo` dataset and the
    real Open-Meteo, at fewer than 40 reports a minute;
  - the p95 of `POST /incidents` in the report experiments of the load tests
    (`backend/load-tests/practice`, [ADR-0023](0023-load-test-observability.md)), with
    `OPEN_METEO_BASE_URL` pointing to a fake server or the worker off, so that a load test never
    calls the real Open-Meteo;
  - calls to Open-Meteo per minute, hour and day, from the adapter's metrics.
- **Threshold:**
  - at least 90 % `COMPLETE` within 60 seconds of the report;
  - the p95 of the report stays under QA-1's 500 ms;
  - calls under 10 % of each free limit, counted only in the minutes, hours and days in which
    Open-Meteo answers: fewer than 60 a minute, 500 an hour and 1,000 a day. During an outage the
    worker bounds them instead, at two calls per run.
- **Review:** at the first measurement on the demo machine, before a second backend replica, and
  when traffic is decided ([ADR-0024](0024-traffic-provider.md)). If the calls go over their
  threshold, the cache trigger of ADR-0022 is evaluated; if the 90 % is missed, this ADR is.

QA-2 in the [architecture overview](../architecture.md) covers the outage of the source.

## Links Related

- [ADR-0009: Use OpenData from Málaga as primary urban data source with OSM fallback](0009-urban-data-source.md)
- [ADR-0010: Start with a modular monolith](0010-modular-monolith.md)
- [ADR-0011: Deploy with docker-compose and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0023: See the load tests in Grafana, with the backend's metrics from Micrometer](0023-load-test-observability.md)
- [ADR-0022: Add Redis only when a measurement or a second replica asks for it](0022-redis-from-measurements.md)
- [ADR-0024: Use TomTom Traffic Flow for the traffic level of an incident](0024-traffic-provider.md) (*Proposed*)
- Issue #305 (this decision); feature #304 and its sub-issues #306, #307, #308 and #309
- Migration `V4__urbancontext_create_context_tables.sql` (`urban_context`, `external_observation`)
- C4 model: external system `Open-Meteo`, used by the `urbancontext` component
- Open-Meteo: https://open-meteo.com/ (terms: https://open-meteo.com/en/terms)
