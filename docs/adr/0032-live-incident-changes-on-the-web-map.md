# ADR-0032: Show the status changes on the web map by polling only for what changed

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-11-22

## Context

#268 asks to see on the staff map, as it happens, when a technician accepts an incident or works on
it. Today the map cannot show that:

- The web map reads its data again every 15 seconds (`MAP_REFRESH_INTERVAL_MS` in
  `frontend/apps/web/src/incidents/incident-queries.ts`, #196). Each refresh sends three requests:
  the positions (`GET /incident-locations`, up to 1,000), the counts of the dashboard
  (`GET /incident-statistics`, #177) and the latest five incidents (`GET /incidents`), plus the
  detail of the selected incident when one is open. TanStack Query stops the timer while the tab is
  hidden.
- With a refresh every *T* seconds, a change waits between 0 and *T* seconds for the next one, plus
  the response time: the p95 of the delay is about 0.95 *T* plus the response. With 15 seconds, it is
  about 14.5 seconds.

The first step of #268, an animation of the `IN_PROGRESS` markers and a mark on a marker whose
status changed at the last refresh, needs none of this ADR. This ADR is about the second step: a
change shown on every open map **within 3 seconds at p95**. That target is written in #268 by the PO
(2026-11-22); the team confirms it or changes it when it reviews this ADR.

**What the current refresh costs.** The quality-attribute experiments of #176 (2026-11-13, developer
laptop, `perf` dataset with 100,000 incidents; [datasets guide](../datasets.md#quality-attribute-experiments-176))
measured the map and the dashboard as the requests that load the database most. All of them used an
operator, who sees every incident:

- A map request holds one of the 10 database connections for about 100 ms (experiment 3), and the
  counts take about 1.4 times as long (experiment 1). The first page of a list takes about 17 ms
  (QA-1). So one refresh of one operator's map holds a connection for about 0.26 seconds.
- The staff mix saturates at 125–140 requests per second, and the limit is the 10 connections of
  PostgreSQL, not the backend (experiment 2).
- With 40 users on the map, at one map request per second each, the p95 of a citizen's report
  triples during a spike of reports, from 13 to 38 ms (experiment 3).
- The map and the counts grow with the data: with 1,000,000 incidents the map takes 2.3 seconds at
  p95 (experiment 4).

The `perf` dataset has 10 operators and 40 technicians. A technician's map only reads their own
assignments, and its cost has not been measured; it is cheaper than an operator's. So the table gives
the 10 operators as the measured case, and the 50 at an operator's cost as an upper bound:

| Refresh every | p95 of the delay | 10 operators: requests per second, pool busy | 50 users, upper bound |
| --- | --- | --- | --- |
| 15 s (today) | about 14.5 s | 2 per second, 0.17 of 10 connections (2 %) | 10 per second, 0.9 of 10 (9 %) |
| 3 s | about 3 s | 10 per second, 0.9 (9 %) | 50 per second, 4.3 (43 %) |
| 2 s (meets the target) | about 2 s | 15 per second, 1.3 (13 %) | 75 per second, 6.5 (65 %) |

Meanwhile, almost nothing changes. Leaving out the first entry of each incident, the `perf` history
has about 387,000 transitions in eleven and a half months: about 1,100 a day, two or three per minute
in a working day. Reading the whole map every 2 seconds would repeat the heaviest queries of the API
to find, nearly every time, that nothing changed. And that cost grows with every new user who opens
the map and with every new incident in the database.

**Constraints of this system:**

- **Authentication** ([ADR-0004](0004-authentication-strategy.md), [security guide](../security.md#-access-tokens)):
  the access token travels in the `Authorization` header and lives 15 minutes; the shared API client
  refreshes it once for all the requests that get `401`, because two refreshes with the same refresh
  token revoke the session (#125). There is no cookie. The browser's `EventSource` and `WebSocket`
  cannot add the header.
- **Modular monolith** ([ADR-0010](0010-modular-monolith.md)): every status change goes through
  `IncidentLifecycleService.changeStatus` in the `incidents` module, with no application events
  between modules. The target architecture of #327 keeps the incidents in the core backend, and adds
  services and the communication between them (#330).
- **Deployment** ([ADR-0011](0011-deployment-12factor.md), [ADR-0017](0017-reverse-proxy.md)):
  one backend replica behind nginx (`frontend/nginx.conf`), with `proxy_read_timeout 30s`, response
  buffering on, and no HTTPS yet.
- **Live data is never cached** ([ADR-0022](0022-redis-from-measurements.md)), and there is no
  broker or Redis.
- **Ordering.** `IncidentPosition` has no version or date, so the client cannot tell which of two
  answers about the same incident is newer. The `incident` table already has a `version`, the
  optimistic lock that every change of that row increments, every transition included.

How should the open web maps learn, within 3 seconds, that the status of an incident changed?

## Decision

The web map **asks every 2 seconds only for the incidents whose status changed** since its last
question, and keeps the full refresh every 15 seconds as the source of truth. No new channel or
connection type is added.

- **Endpoint.** `GET /api/v1/incident-changes?since=<cursor>`, an ordinary JSON operation of the
  contract (its name is final in the pull request that adds it). It answers the incidents that had a
  status transition after the cursor, each with its id, current status, `version`, priority,
  department and position, and the cursor for the next question. The first question, with no
  cursor, returns only a cursor.
- **Transitions only.** A new report also writes a `REPORTED` row in `status_change`
  (`Incident.report`); the query leaves those rows out (`previous_status IS NOT NULL`). A spike of
  reports therefore adds nothing to the answers, and new incidents keep arriving with the 15-second
  refresh, as today.
- **The cursor** is an instant read from the database clock, never from the client's. The query
  reads a fixed overlap before it (30 seconds, set in the pull request), so a transaction that
  commits after a later one is not skipped; an incident that comes back twice is recognised by its
  `version`. A change whose transaction takes longer than the overlap is still shown by the next
  15-second refresh. More than 200 changes in one answer (a tab that was asleep) return
  `truncated: true` with a cursor taken before the answer, and the client does one full refresh.
- **Permissions.** The same as `GET /incident-locations`, through the same rule
  (`IncidentReadAccess`): operators and administrators see every change, a technician only those of
  incidents assigned to them at the time of the question, and citizens and analysts get `403`. A
  technician whose incident was reassigned stops getting its changes at once, and its marker leaves
  their map with the next 15-second refresh.
- **Cost.** A new index on `status_change (instant)` makes it a short indexed read of a few rows. Its
  time is measured in the evaluation below. At an estimated 5 ms, 50 maps asking every 2 seconds keep
  0.13 of the 10 connections busy; with the 15-second full refresh that stays, the total is about 1
  connection (10 %), against 6.5 for a full refresh every 2 seconds.
- **Ordering.** `IncidentPosition` gets the incident's `version` too (a change of the contract), and
  the client keeps, for each incident, the answer with the highest one. So neither an older full
  refresh nor a repeated change takes a marker back.
- **What the client does with a change.** It updates a marker it already shows, and hides it if the
  new status or department is outside the map's filters. A transition changes no other field the
  filters use (priority, category, dates). A change of an incident that is not on the map is ignored:
  the map shows at most 1,000 incidents, so most changes are of incidents it does not show, and
  reading the positions again for each would bring back the full refresh every 2 seconds. Such an
  incident appears with the next 15-second refresh. If the incident is the one selected, the client
  reads its detail again, so the panel never shows an older status than its marker. The counts of
  the dashboard and the latest five keep the 15-second refresh, and may be up to 15 seconds behind
  the markers.
- **The rest is what the app already does.** The requests go through the shared API client and
  TanStack Query: the same token refresh, the same retries, the same pause while the tab is hidden.

## Considered Options

- **Keep the 15-second refresh** — No change, but the p95 of the delay stays near 15 seconds and
  #268's target is not met. It is still the answer if the team drops the target: the first step of
  #268 works with it.
- **Refresh the whole map every 2 seconds** — Rejected. It needs no new code and meets the target, at
  13 % of the pool for the 10 operators. But its cost grows with every user who keeps the map open (up
  to 65 % with the 40 technicians at an operator's cost) and with the data (2.3 seconds per map
  request with 1,000,000 incidents), and experiment 3 already measured that map requests at a similar
  rate triple the latency of the reports in a spike.
- **Poll only for the changes every 2 seconds** — Selected. It meets the target (a p95 of about 2
  seconds). Each question costs about a fiftieth of a full refresh, and with the 15-second refresh
  kept the database does about a sixth of the work of a full refresh every 2 seconds; the questions
  themselves do not grow with the data. It reuses the API client, the token refresh, TanStack Query,
  the contract tests and k6, and it keeps working with more than one backend replica or a gateway in
  front of the core.
- **Server-sent events** — The backend pushes each committed change through one stream per map
  (Spring MVC `SseEmitter`, read with `fetch` so that it can send the `Authorization` header). It
  delivers in well under a second and costs the database nothing while idle, but no requirement asks
  for under a second, and it brings a new kind of connection with its own failures:
  - the stream needs its own timeout, because Spring's default asynchronous timeout ends it after 30
    seconds;
  - it needs a heartbeat under nginx's 30-second read timeout and `X-Accel-Buffering: no` so that
    nginx does not hold the events, and both are only proved through nginx;
  - a stream reopened with an expired token must go through the shared refresh, or two refreshes
    revoke the session (#125);
  - its registry of streams lives in the backend process, so it only works with one replica and
    without a gateway that buffers;
  - each tab keeps one of the browser's six HTTP/1.1 connections to the host open until HTTPS
    brings HTTP/2;
  - k6 cannot measure it without an extension.

  Revisit it if a target under one second appears (see *Review* below).
- **WebSocket** — Rejected. The map only receives. It adds `spring-boot-starter-websocket` and usually
  STOMP, its own security for the handshake and the `Upgrade` headers in nginx, and the browser's
  `WebSocket` cannot send the `Authorization` header either.
- **Long polling** — A request that waits until something changes. It has the timeouts and proxy
  limits of a stream, without its standard format.
- **A token in the URL, or in a cookie, for `EventSource`** — Rejected with server-sent events. In the
  URL it ends up in the nginx access logs and the browser's history. A cookie brings back CSRF, which
  is off because no cookie carries a credential ([security guide](../security.md)), and needs a
  change of ADR-0004.

## Consequences

- **Positives:**
  - A status change reaches the open maps within about 2 seconds at p95, with no new container,
    dependency, connection type or proxy configuration.
  - The cost of the map no longer depends on refreshing everything often: the full refresh stays at
    15 seconds, and the extra questions are cheap and do not grow with the data.
  - Permissions stay in one rule on the server, and the client learns only what it may already read.
  - Every failure is an ordinary request failure, which the app already handles.
- **Negatives:**
  - 25 requests per second with 50 open maps, nearly all of them empty, in the access logs and the
    metrics. They count against no rate limit today (nginx limits only the sign-in operations).
  - A new index on `status_change`, which every status change also writes.
  - `IncidentPosition` changes: the generated clients get a `version` field.
  - Only transitions of incidents already on the map arrive quickly. A new report, an incident that
    enters the map, a new category or a change of priority without a transition, and a change whose
    transaction outlasts the overlap wait for the 15-second refresh.
  - With more than one backend replica, the overlap must also cover the difference between their
    clocks, because `status_change.instant` is set by the application.

## How it will be evaluated

- **Metric:** with the `perf` dataset, on the conditions of QA-1, a k6 script with 10 operators and 40
  technicians that each ask for the changes every 2 seconds and refresh the map every 15, while
  another user changes the status of incidents on those maps (`POST /incidents/{id}/acceptance`,
  among others) 100 times, during normal load and during the spike of experiment 3: the time from
  each change's response to its first appearance in the answer of every map that may see it; the
  p95 of the changes operation; the active connections of the pool (`hikaricp_connections_active`,
  Grafana of [ADR-0023](0023-load-test-observability.md)); and experiment 3 of #176 repeated with
  those 50 maps.
- **Threshold:** a p95 delay under 3 seconds and every change within 15 seconds; the changes operation under 50 ms
  at p95; the p95 of the reports in experiment 3 no worse than with the 15-second refresh alone.
- **Review:** if a target under one second appears, or a page that must react to many kinds of change
  (then server-sent events are compared again, with the points above); if the changes operation
  misses its threshold; and when the mobile app gets a map of other people's incidents.

It is measured on the same conditions as QA-1 in the
[architecture overview](../architecture.md#qa-1-response-time-of-the-incident-list-and-detail), whose
numbers this ADR uses.

## Links Related

- Issue #268 (this decision and its acceptance criteria); #196 (the web map and its refresh); #177
  (the dashboard refreshed with it); #176 (the measurements); #125 (refresh-token reuse)
- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0010: Start with a modular monolith](0010-modular-monolith.md)
- [ADR-0011: Deploy with docker-compose and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0017: nginx as the single entry point in the Docker deployment](0017-reverse-proxy.md)
- [ADR-0020: Incident lifecycle transitions](0020-incident-lifecycle-transitions.md)
- [ADR-0022: Add Redis only when a measurement or a second replica asks for it](0022-redis-from-measurements.md)
- [ADR-0023: See the load tests in Grafana, with the backend's metrics from Micrometer](0023-load-test-observability.md)
- C4 model: no change. The map keeps talking to the backend API over the existing relationship
