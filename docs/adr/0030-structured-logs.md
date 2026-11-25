# ADR-0030: Structured logs in JSON, collected with Loki next to the metrics

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-11-25
- **Design document:** None. DD-004 (#363) builds on it for the services and may amend it.

## Context

The backend writes its log to stdout as plain text (Twelve-Factor XI), and every line of a request
carries its id: `RequestIdFilter` puts it in the MDC as `requestId`, returns it in `X-Request-ID` and
repeats it as `traceId` in every Problem body, and `logging.pattern.correlation` prints it
(`application.yaml`). Nothing collects those lines: they are read with `docker compose logs`, one
container at a time, and only by searching the text.

[ADR-0023](0023-load-test-observability.md) set up Prometheus and Grafana in the `observability`
profile of the development Compose for the load tests: metrics, not logs. The final delivery asks
for an observability environment that combines k6, Micrometer, **structured logs**, Prometheus and
Grafana (*Final-delivery requirements* in the [requirements](../requirements.md), #362), and the
move to services (DD-001, #412) multiplies the places where one request leaves lines.

The decision has to be taken before the services: the logs (#365) and the SLI dashboards (#366) are
built on the monolith, and the baseline (#371) is measured with them on v0.5.0.

What format should the logs have, how are they collected and read next to the metrics, and how is one
request followed across services?

## Decision

- **Format: JSON in the ECS format of Spring Boot's structured logging**
  (`logging.structured.format.console: ecs`), one object per line on stdout. It is built into Spring
  Boot, so it needs no new dependency, and it turns every MDC entry into a field.
- **It is on wherever the backend runs in a container:** both Compose files set
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for the backend. Run from the IDE or with
  `./mvnw spring-boot:run`, the log stays plain text, which a person reads more easily.
- **Fields:** the ones of ECS (`@timestamp`, `log.level`, `log.logger`, `process.thread.name`,
  `message`, `error.*`), `service.name` from `spring.application.name` (`urbanpulse` for the core),
  and two from the MDC: `requestId` in every line of a request, as today, and `incidentId`, the incident's public
  UUID, while a request or a job works on one incident.
- **Never logged:** passwords, tokens, refresh tokens, challenge tokens, second-factor secrets and
  codes, request and response bodies, query strings, emails, names, addresses, coordinates and the
  content of photos. A user appears only by their id, as today ([security guide](../security.md)).
- **Collection: Loki, fed by Grafana Alloy**, in the same `observability` profile as Prometheus and
  Grafana, with pinned images and ports on `127.0.0.1` only. Alloy discovers the containers through the
  Docker socket and sends their stdout to Loki; Grafana gets Loki as a second data source.
- **Labels stay few:** `service` and `level` only. `requestId` and `incidentId` are fields searched with
  LogQL (`| json | requestId="..."`), never labels, because one label value per request would make
  Loki create one stream per request.
- **Retention:** Loki keeps the logs of the profile for 7 days, enough for a measurement and its
  analysis, so the volume stays bounded on a developer's machine.
- **Following a request across services: the request id, not distributed tracing.** Each service keeps
  `RequestIdFilter`, and every call the core makes to another service sends the `X-Request-ID` of the
  request it is serving, so the same id appears in the lines of every service it reached.
- **The deployment does not run Loki.** Its backend also writes JSON to stdout, read with
  `docker compose logs` as today; the deployment Compose gains no container.

## Considered Options

- **Format**
  - **ECS of Spring Boot's structured logging** (chosen): no dependency, a documented field set that
    Loki, Elasticsearch and most tools understand, and the MDC included without code.
  - **Logstash format of Spring Boot**: also built in; its field names (`level`, `logger_name`) are
    less standard than ECS's, and nothing here needs Logstash.
  - **A custom encoder** (`logstash-logback-encoder`): full control over every field, at the cost of a
    dependency and of Logback configuration to maintain, for fields ECS already has.
  - **Plain text, parsed at collection**: no change in the backend, but every parser breaks when a
    message has a newline or a pattern changes, and each service would need its own.
- **Collection**
  - **Loki with Grafana Alloy** (chosen): Loki indexes only the labels, so it is light enough for one
    machine; it is read in the Grafana that already shows the metrics; Alloy is the collector Grafana
    maintains, now that Promtail is deprecated in its favour.
  - **Loki with Promtail**: the same result, with a collector its maintainers have deprecated.
  - **The Loki Docker logging driver**: no collector container, but a plugin installed in the Docker
    daemon of every machine, outside the repository, and a container cannot start if Loki is down.
  - **Elasticsearch or OpenSearch with Kibana**: full-text search, at the cost of a much heavier store
    (gigabytes of memory) and a second user interface next to Grafana.
  - **Only `docker compose logs`**: nothing to run, but no search across services, no view next to the
    metrics, and no history once a container is recreated.
- **Across services**
  - **Request id in the logs and in `X-Request-ID`** (chosen): already built in the backend, nothing new
    to run, and enough to answer "what did this request do in each service".
  - **Micrometer Tracing with OpenTelemetry and a trace store such as Tempo**: the time spent in each
    service and each call, at the cost of an exporter in every service and two more containers. It is
    the option DD-004 reconsiders if the measurements of #367 need to know where the time goes.

## Consequences

- **Positives:**
  - The `traceId` a user reports, or one of a failed k6 request, leads to every line of that request
    with one query in Grafana, next to the metrics of the same minutes.
  - The logs and the metrics are read in one place, with the same time range.
  - No new dependency in the backend; the change in its code is the `incidentId` in the MDC and the
    `X-Request-ID` sent to other services.
- **Negatives:**
  - Two more containers (Loki and Alloy) and their images to keep up to date, in the profile only.
  - Alloy reads the Docker socket, which gives it control of the Docker daemon. That is acceptable only
    on a developer's machine, in the profile, and it is a reason the deployment does not run it.
  - A JSON line is harder to read in a terminal than plain text; `docker compose logs` needs `jq` to be
    pleasant, and runs outside a container keep plain text.
  - A request whose work continues in a message or a scheduled job loses its `requestId` unless the id
    travels with the message; the asynchronous flows of DD-001 have to carry it.

## How it will be evaluated

- **Metric:** during a k6 run of the baseline (#371) with the `observability` profile on, the `traceId`
  of a failed response is searched in Grafana; once the services exist (#367), the same with a request
  that crosses them.
- **Threshold:** one LogQL query by `requestId` returns every log line of that request, from every
  service it reached, and no line holds any of the values listed under *Never logged* (checked by the
  test of #365).
- **Review:** after #367, or before if an asynchronous flow cannot carry the request id, or if the
  measurements need the time per service: then distributed tracing is reconsidered in DD-004.

## Links Related

- [ADR-0023](0023-load-test-observability.md): Prometheus and Grafana for the load tests, which this
  ADR extends with logs
- #364 (this ADR), #365 (the structured logs), #366 (the SLI dashboards), #362 (the observability
  environment), #178 (Loki, closed as a duplicate of #365)
- [Twelve-Factor evidence](../twelve-factor.md), *XI. Logs*
- [Security guide](../security.md): the secrets it already keeps out of the logs (second factor,
  `JWT_SECRET`, the egress proxy)
