# ADR-0024: Use TomTom Traffic Flow for the traffic level of an incident

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-11-17 (split from the first version of ADR-0018, proposed on 2026-10-25)

## Context

The `urban_context` table (migration `V4__urbancontext`) stores the traffic level around an
incident in the enum column `traffic_status` (LOW, MEDIUM, HIGH). Every raw value must also be an
`external_observation` with its source, observation instant, ingestion instant, unit and quality
([ADR-0009](0009-urban-data-source.md)).

The first version of [ADR-0018](0018-weather-and-traffic-providers.md) chose Open-Meteo for the
weather and TomTom for the traffic. The MVP takes only the weather (#304), so ADR-0018 was
accepted for the weather alone and the traffic moved here, still to be decided:

- **No source has live traffic by coordinates yet.** OpenStreetMap and MapLibre hold no live data
  ([ADR-0016](0016-self-hosted-osm-base-map.md)). The Open Data Málaga catalogue has not been
  audited for traffic feeds ([ADR-0009](0009-urban-data-source.md)).
- **TomTom needs an account, a key and its own allowance**, which the team has not set up.

Which external source should provide the traffic level of an incident, and how is its value
turned into the enum of the schema?

## Decision

Proposed: the `urbancontext` module ([ADR-0010](0010-modular-monolith.md)) gets the current
traffic flow from the **TomTom Traffic Flow API (Segment Data)**, in the same worker that fetches
the weather ([ADR-0018](0018-weather-and-traffic-providers.md)), with the same rules: the report
only creates a `PENDING` context, the call has a short timeout, a failure never blocks the report,
and retries are bounded by time.

- **Request:** the segment nearest to the incident point. The response includes `currentSpeed`,
  `freeFlowSpeed`, `confidence` and `roadClosure`.
- **Mapping to `traffic_status`:** the ratio `currentSpeed / freeFlowSpeed` is mapped as follows.
  The thresholds are a starting point; the team reviews them with real data.

  | Ratio | `traffic_status` |
  |---|---|
  | ≥ 0.75 | LOW |
  | 0.50 – 0.75 | MEDIUM |
  | < 0.50, or `roadClosure = true` | HIGH |

- **Quality:** TomTom's `confidence` is stored as the `quality` of the observation.
- **Two values, one context:** with the weather and the traffic, a context with only one of them
  is `PARTIAL`. How a run stops when one provider fails and the other answers, and what a context
  is when `TOMTOM_API_KEY` is missing, are decided when this ADR is accepted.
- **Key:** `TOMTOM_API_KEY` comes from the environment
  ([ADR-0011](0011-deployment-12factor.md)). It is never stored in the database or in Git, and
  without it the traffic is not requested.
- **No cache** until a measurement asks for it ([ADR-0022](0022-redis-from-measurements.md)). If
  the measured requests approach TomTom's allowance, that is a trigger of ADR-0022.

## Considered Options

- **TomTom Traffic Flow.** A free allowance with no credit card, a point query, and a speed
  ratio that maps directly to LOW/MEDIUM/HIGH.
- **Open Data Málaga traffic datasets.** Would be the authoritative source, but their existence
  and format are unknown until the catalogue audit of ADR-0009. If the audit finds a usable feed,
  it can replace TomTom behind the same adapter.
- **HERE Traffic or Google Routes.** Similar data to TomTom, but they need billing set up from the
  start.
- **No traffic.** The `traffic_status` column stays without data, as in the MVP.

## Consequences

- **Positives:**
  - The `traffic_status` column gets a real source and a documented mapping.
  - It reuses the worker, the outcomes and the provenance of ADR-0018.
- **Negatives:**
  - One more external dependency with its own timeout and failure mode.
  - One more secret to manage (`TOMTOM_API_KEY`).
  - TomTom's free allowance and terms (including how long derived values may be stored) can
    change; they must be checked before this ADR is accepted.
  - The traffic thresholds are a guess until they are compared with real Málaga data.

## How it will be evaluated

- **Metric:** share of contexts with a `traffic_status`, and TomTom requests per day, from the
  adapter's metrics, on the demo machine.
- **Threshold:** at least 90 % of the contexts with a traffic level while TomTom answers, and the
  requests under 10 % of the free allowance in force when the ADR is accepted.
- **Review:** when the team decides whether traffic enters a milestone, and after the Open Data
  Málaga catalogue audit.

## Links Related

- [ADR-0018: Use Open-Meteo for the weather of an incident, enriched after the report with no cache](0018-weather-and-traffic-providers.md)
- [ADR-0009: Use OpenData from Málaga as primary urban data source with OSM fallback](0009-urban-data-source.md)
- [ADR-0011: Deploy with docker-compose and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0022: Add Redis only when a measurement or a second replica asks for it](0022-redis-from-measurements.md)
- Issue #305 (the split from ADR-0018)
- C4 model: external system `TomTom Traffic`
- TomTom Traffic API: https://developer.tomtom.com/traffic-api/documentation (pricing: https://docs.tomtom.com/pricing)
