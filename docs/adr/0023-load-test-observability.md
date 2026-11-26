# ADR-0023: See the load tests in Grafana, with the backend's metrics from Micrometer

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-11-15

## Context

The quality attributes are measured with k6 (QA-1 in the [architecture overview](../architecture.md),
#155), and [ADR-0022](0022-redis-from-measurements.md) adds Redis only when a measurement asks for it.
k6 alone prints a summary at the end of each run: it says how slow the API is, not why, and two runs
can only be compared by reading two summaries.

The quality-attribute practice of the course (#176) asks for four experiments with several load
models, three runs per configuration, and for the diagnosis of what limits the system. That needs
the k6 results over time, run by run, and next to them what happens inside the backend during the
same seconds: the database connections in use and waiting, the CPU, the JVM and the latency per
endpoint.

How should the k6 results and the backend's metrics be collected and seen together?

## Decision

- k6 sends its results to **Prometheus by remote write** (`-o experimental-prometheus-rw`), with
  p50, p95, p99 and the maximum of every trend, and a `testid` tag per run.
- The backend exposes its **Micrometer** metrics in the Prometheus format at
  `/actuator/prometheus`, with the latency histogram of `http.server.requests`; Prometheus scrapes
  it every 5 seconds. It needs no token: it is only reachable where the backend's port is, never
  through the nginx of the deployment ([security guide](../security.md)).
- **Grafana** shows both with two provisioned dashboards, one for k6 and one for the backend.
- Prometheus and Grafana run in an `observability` profile of the development Compose
  (`backend/docker-compose.yaml`), with pinned images and ports on `127.0.0.1`. Nothing starts
  without the profile, and the deployment Compose does not include them.

## Considered Options

- **k6 summary and its web dashboard only** (`K6_WEB_DASHBOARD`): nothing to run, an HTML report per
  run. It cannot show the backend's metrics, so it measures the symptom without the cause, and it
  does not compare runs side by side. It stays available for a quick run (load-tests README).
- **InfluxDB for k6, as in the older k6 guides**: a second time-series store next to Prometheus,
  which the backend's metrics need anyway.
- **OpenTelemetry Collector with traces**: it would also give the time inside each request, but
  it adds a collector, an exporter in the backend and a trace store for a question the metrics
  already answer.
- **Prometheus by remote write, Micrometer and Grafana** (chosen): one store for both sources,
  Micrometer is the metrics library Spring Boot already ships with the Actuator, and remote write is
  supported by k6 without extensions.

## Consequences

- **Positives:**
  - The first diagnosis with it found the bottleneck: under saturation every database connection
    was busy with about 190 requests waiting, while the backend used 8 % of CPU
    (`backend/load-tests/practice/RESULTS.md`, experiment 2).
  - Each run stays in Prometheus under its `testid`, so runs are compared in the same dashboard.
  - A business metric (`urbanpulse_incidents_reported_total`) shows next to the technical ones.
- **Negatives:**
  - `/actuator/prometheus` is a public path of the backend. It holds counts and timings, no request
    data or users, and is safe only while the backend publishes no port beyond `127.0.0.1` and
    nginx does not proxy `/actuator`. A backend run from the IDE listens on every interface, so
    there the metrics are visible from the local network. The `lint` job already fails if a service other than `web`
    publishes a port in the deployment.
  - Two more images to keep up to date, and Docker resources while the profile runs.
  - k6's remote write output is still marked experimental by k6.

## How it will be evaluated

- **Metric:** a load test run with the profile on shows, in Grafana, the k6 latency per operation and
  the backend's connections and CPU for the same minutes.
- **Threshold:** the cause of a slow run can be told from the two dashboards without reading logs.
- **Review:** when the platform has a cloud deployment with monitoring of its own, or when traces
  are needed; then this profile is replaced by that monitoring, or OpenTelemetry is reconsidered.

## Links Related

- [ADR-0022](0022-redis-from-measurements.md): Redis only from measurements
- [ADR-0030](0030-structured-logs.md) (Proposed): the structured logs, collected with Loki in the same
  profile
- #176 and its `[Test]` #297
- [Load tests and results](../../backend/load-tests/practice/README.md)
- [Datasets and measurements](../datasets.md)
