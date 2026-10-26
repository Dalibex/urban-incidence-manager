# ADR-0018: Use Open-Meteo for weather and TomTom for traffic context

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-25

## Context

The `urban_context` table (migration `V4__urbancontext`) stores the weather and the traffic
level around an incident in two enum columns, `weather` (SUNNY, WINDY, CLOUDY, PARTLY_CLOUDY,
RAINY, STORMY, SNOWY, FOGGY) and `traffic_status` (LOW, MEDIUM, HIGH). Every value must also be
recorded as an `external_observation` with its source, observation time, ingest time, unit and
quality, as [ADR-0009](0009-urban-data-source.md) requires.

No source has been chosen for these two values:

- **OpenStreetMap and MapLibre cannot provide them.** OSM holds static map data (roads,
  buildings, places) and MapLibre only draws the map ([ADR-0016](0016-self-hosted-osm-base-map.md)).
  Neither has weather or live traffic.
- **Open Data Málaga** ([ADR-0009](0009-urban-data-source.md)) is the primary source of urban
  data. Its catalogue has not been audited yet, and nothing so far shows that it publishes
  real-time weather or traffic that can be queried by coordinates.
- The C4 model showed a placeholder "Weather & Traffic Sources (AEMET / municipal traffic, to
  be defined)" with no decision behind it.

Which external sources should provide the weather and the traffic level of an
incident, and how are their values turned into the enums of the schema?

## Decision

The `urbancontext` module ([ADR-0010](0010-modular-monolith.md)) will get current weather from
**Open-Meteo** and current traffic flow from the **TomTom Traffic Flow API**. Both are called
during the asynchronous enrichment that runs after an incident is created
([ADR-0009](0009-urban-data-source.md)).

### Weather: Open-Meteo

- **Request:** the current-weather endpoint, called with the incident coordinates. It returns
  the WMO weather code, wind speed and precipitation. No API key and no account are needed.
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
  | any code without precipitation and wind ≥ 40 km/h | WINDY |

- **Attribution:** Open-Meteo data is licensed under CC BY 4.0, which requires attribution. The
  incident context view shows "Weather data by Open-Meteo.com".

### Traffic: TomTom Traffic Flow (Segment Data)

- **Request:** the segment nearest to the incident point. The response includes
  `currentSpeed`, `freeFlowSpeed`, `confidence` and `roadClosure`.
- **Mapping to `traffic_status`:** the ratio `currentSpeed / freeFlowSpeed` is mapped as
  follows. The thresholds are a starting point; the team reviews them with real data.

  | Ratio | `traffic_status` |
  |---|---|
  | ≥ 0.75 | LOW |
  | 0.50 – 0.75 | MEDIUM |
  | < 0.50, or `roadClosure = true` | HIGH |

- **Quality:** TomTom's `confidence` is stored as the `quality` of the observation.

### Common rules

- **One adapter per provider** in `es.uma.urbanpulse.urbancontext`, behind interfaces
  `WeatherProvider` and `TrafficProvider`. Changing a provider, for example to AEMET or to
  municipal traffic data if the catalogue audit finds it, replaces one adapter and nothing
  else.
- **Cache** in Redis ([ADR-0012](0012-cache-redis.md)). The key is the coordinates rounded to
  about 1 km. The TTL is 15 minutes for weather and 5 minutes for traffic. This keeps
  TomTom inside its free allowance.
- **Never blocks the report.** Each call has a short timeout. On a timeout or error, the
  `urban_context.status` is set to PARTIAL or UNAVAILABLE and the incident is still created
  ([ADR-0009](0009-urban-data-source.md)).
- **Configuration** follows twelve-factor ([ADR-0011](0011-deployment-12factor.md)):
  - Base URLs and the on/off switch per source go in `data_source_config`.
  - The TomTom key comes from the environment variable `TOMTOM_API_KEY`. It is never stored
    in the database or in Git.
- **Provenance.** Each raw value (WMO code, wind speed, current and free-flow speed) is stored
  as an `external_observation`. The record keeps the source name, unit, observation time and
  ingest time, plus an expiration date equal to the cache TTL.

**Hypothesis and evaluation:**

- If weather and traffic come from Open-Meteo and TomTom with the cache above, at least 90 % of
  incidents get both values within 10 seconds of creation, at zero cost.
- **Metrics:**
  - share of `urban_context` rows with status COMPLETE;
  - enrichment latency;
  - TomTom requests per month.
- **Thresholds:**
  - COMPLETE in at least 90 % of rows;
  - fewer than 20,000 TomTom requests per month (the free allowance at the time of writing).
- **Review:** at the first sprint retrospective after enrichment is deployed.

## Considered Options

- **OpenStreetMap / MapLibre.** No weather or traffic data, so it is not an option. It is
  listed only because the question was asked.
- **AEMET OpenData** (Spain's national weather agency). Official and free, but it needs an API
  key and answers in two steps: one request returns a URL, and a second request returns the
  data. It is oriented to forecasts per municipality or station rather than current conditions
  at a point. It remains the fallback if Open-Meteo's terms become a problem.
- **OpenWeatherMap.** Current weather by point, but it needs an account and an API key, and
  its free tier has a daily limit.
- **Open-Meteo.** No key, current weather by coordinates, a simple JSON response, and WMO codes
  that map directly to the enum. Selected for weather.
- **Open Data Málaga traffic datasets.** Would be the authoritative source, but their
  existence and format are unknown until the catalogue audit of ADR-0009. If the audit finds
  a usable feed, it can replace TomTom behind `TrafficProvider`.
- **HERE Traffic or Google Routes.** Similar data to TomTom, but they need billing set up from
  the start.
- **TomTom Traffic Flow.** Free monthly allowance with no credit card, a point query, and a
  speed ratio that maps directly to LOW/MEDIUM/HIGH. Selected for traffic.
- **No weather or traffic.** This would leave two columns of the schema without data and the
  context view incomplete.

Open-Meteo and TomTom were selected because they answer exactly the question the schema asks
(the conditions at this point, now) with no cost and the least integration work. The adapter
interface keeps the door open for the municipal or national sources.

## Consequences

- **Positives:**
  - The `weather` and `traffic_status` columns have a real source and a documented mapping.
  - Open-Meteo needs no key at all. TomTom needs one key, kept in the environment.
  - Both providers sit behind interfaces, so they can be replaced without touching incidents
    or the schema.
  - Cache plus asynchronous enrichment keep usage low and never delay a citizen report.
- **Negatives:**
  - **Open-Meteo's free API is for non-commercial use only.** That is fine for this project.
    A commercial deployment would need a paid plan or a switch to AEMET.
  - Two more external dependencies, each with its own timeout, cache and failure mode.
  - TomTom's free allowance and terms (including how long derived values may be stored) can
    change. They must be checked before any production use.
  - The traffic thresholds are a guess until they are compared with real Málaga data.
  - The TomTom key adds one more secret to manage ([ADR-0011](0011-deployment-12factor.md)).

## Links Related

- [ADR-0009: Open Data Málaga as primary urban data source](0009-urban-data-source.md)
- [ADR-0010: Modular monolith](0010-modular-monolith.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0012: Redis cache](0012-cache-redis.md)
- Migration `V4__urbancontext_create_context_tables.sql` (`urban_context`, `external_observation`)
- C4 model: external systems `Open-Meteo` and `TomTom Traffic`, used by the `urbancontext` component
- Open-Meteo: https://open-meteo.com/ (pricing and terms: https://open-meteo.com/en/pricing)
- TomTom Traffic API: https://developer.tomtom.com/traffic-api/documentation (pricing: https://docs.tomtom.com/pricing)
