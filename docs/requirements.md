# 📋 Requirements and Prioritization

The priority of each functional requirement of the case study (RF01–RF26), which issues cover it in
the MVP and after it, the requirements of the final delivery, and the screens each role gets.

> [!NOTE]
> **This document does not repeat the plan.** The GitHub **milestones** say when each issue is done,
> and the **issues** what each feature includes and how it is accepted. When the behaviour of a
> feature is decided elsewhere, that document wins and this one links to it: the lifecycle in
> [ADR-0020](adr/0020-incident-lifecycle-transitions.md), permissions and the audit trail in the
> [security guide](security.md), the API in the [OpenAPI contract](api/openapi.yml), and any other
> decision in its [ADR](adr/).

It replaces the team's *Priorización de Requisitos* of 2026-10-27, which was written before the
backlog was planned on GitHub; *Changes From the Original Prioritization* records where the two
differed and which side was kept.

## 📑 Contents

- **[Two Kinds of Priority](#-two-kinds-of-priority)**: the priority of the case study, and the team's own.
- **[Requirements](#-requirements)**: every requirement, with its priority, status and issues.
- **[Final Delivery](#-final-delivery)**: what the course's final delivery requires, its status and its issues.
- **[Screens per Role](#-screens-per-role)**: what each role gets, built or planned.
- **[Changes From the Original Prioritization](#-changes-from-the-original-prioritization)**: what the team changed, and why.
- **[Keeping This Document Up to Date](#-keeping-this-document-up-to-date)**: when and how it changes.

## 🧭 Two Kinds of Priority

| Priority | Where | What it means | Who sets it |
| --- | --- | --- | --- |
| Requirement priority (High, Medium, Low) | This document | How much the requirement matters to the product | Product owner, with the team |
| Issue priority (`priority:` label) | GitHub issues | In which order the work is done inside a sprint | The issue's author, at planning |

They can differ. An issue may be more urgent than its requirement because other issues wait for it:
the classification of #47 (RF10, Medium) is `priority: high` because validation (#48) needs it.
And a requirement can matter more than its place in the plan suggests, because it waits for an
accepted ADR or for data the platform does not have yet: RF14 is Medium and is not in the MVP. What
must not happen is a feature issue with no requirement, or a High requirement with no issue and no
reason.

## 📌 Requirements

**In the MVP** says how much of the requirement the MVP (Sprint 1 and Sprint 2, until 2026-11-17)
covers: *Yes*, *Partly* (the note says what is left out), *Stretch* (planned only if there is time)
or *No* (after the MVP). **After the MVP** says what the next milestones add and which issue covers
it, and which accepted decision a requirement of the [final delivery](#-final-delivery) replaces.
Every requirement is planned in the monolith before the refactor, except the analyst's (RF17,
RF25) and RF20, which is out of the final delivery. The refactor then moves part of RF18, RF23 and
RF26 into the services of the final delivery (#327, #346, #353). Whether an issue is finished, and
in which milestone, is in GitHub, not here.

### High

| ID | Requirement | In the MVP | Issues | After the MVP |
| --- | --- | --- | --- | --- |
| RF01 | **Identity and access.** Users sign up or sign in; staff authenticate. | Yes, with the second factor for staff (ADR-0021). | #35, #39, #126, #165; #57 (second factor, ADR-0021) | — |
| RF02 | **Roles and permissions.** Different permissions for citizens, operators, technicians, administrators and analysts. | Yes | #35, #39, #100, #40; [permission matrix](security.md#-permission-matrix) | A new **auditor** role that reads the audit with the administrators ([final delivery](#-final-delivery)). Planned: #355. |
| RF03 | **Report an incident** with a title, description, position and optional category. | Yes | #43 | — |
| RF04 | **Location.** The incident keeps coordinates, normalized address and accuracy. | Yes. Coordinates, and the accuracy while the position is the phone's GPS fix; the citizen corrects the position with a pin on the mobile map, and reports without GPS once they move it: the map's starting point on Málaga is never sent. The normalized address is resolved from the position by Nominatim after the report, never delaying it, and shown with its source in the web and mobile details and the staff lists, or said to be pending, outside Málaga or not available ([ADR-0002](adr/0002-open-street-map.md), #375). Incidents reported before it have none. | #43; #60 (#197, #198); #375 | — |
| RF06 | **Consultation and tracking.** A user sees the detail, status, dates and visible history of their incidents. | Yes | #45 (citizen), #46 (staff), #100, #169 (timeline) | — |
| RF09 | **Validation.** An operator validates, requests more information or rejects a report with a reason. | Yes | #48 (#188, #189) | — |
| RF11 | **Assignment** of a validated incident to a department, team or technician. | Yes | #49 (#184, #185), #190 | — |
| RF12 | **Lifecycle.** Status changes follow the allowed transitions and are audited. | Yes | ADR-0020; #37, #99, #101, #102, #49, #51, #52 | — |
| RF18 | **Audit** of administrative actions, assisted decisions and relevant changes. | Partly. Administrative actions and classification; assisted decisions come with RF20. | #36, #40, #41, #47 | Every change of every incident and who made it, an audit dashboard and an audit service with its own database ([final delivery](#-final-delivery)). Planned: #353. Replaces the single schema of ADR-0010 for the audit. |
| RF26 | **Controlled degradation.** An external source being down does not prevent creating an incident. | Yes. Creating an incident calls no external source: the weather and the address are fetched after the report; it has to stay that way when RF21–RF25 arrive. | #43 | It also holds when one of the new services is down ([final delivery](#-final-delivery)). Planned: #327. |

### Medium

| ID | Requirement | In the MVP | Issues | After the MVP |
| --- | --- | --- | --- | --- |
| RF05 | **Evidence.** Photos or other files, with type and size validation. | Yes | #44 (ADR-0013; #192 API, #193 mobile, #194 web) | The photos in an external image service ([final delivery](#-final-delivery)). Planned: #341. Replaces the local volume of ADR-0013. |
| RF07 | **Search.** Staff filter by status, category, date, priority, district and department. | Yes. The district filter needs RF21. A text search on the title and the description came after the MVP, on the web map. | #58 (#182 API, #183 web); #266 (text search) | The district filter comes with RF21. Planned: #59. |
| RF08 | **Map** of incidents and relevant urban layers. | Partly. Incidents on the web map, clustered by zoom and refreshed on their own; the mobile report form places the incident with a pin on the base map; urban layers need urban data, which the MVP does not load. | #53 (#195 API, #196 web); #60 (mobile: #197, #198) | Urban layers on the staff map. Planned: #374. |
| RF10 | **Priority**, manual or assisted, keeping its justification. | Partly. Manual priority with a required justification; assisted priority comes with RF20. | #47 | Assisted priority stays out with RF20. |
| RF13 | **Collaboration.** Operators and technicians add comments, evidence and resolution data. | Yes. Every comment is internal in the MVP. | #50, #51, #44 | — |
| RF14 | **Notifications** of relevant changes through configurable channels. | No. The delivery providers are only proposed (ADR-0019). | — | Notifications to the citizen. Planned: #373; the providers in ADR-0031, Planned: #397. |
| RF19 | **Reports.** Exportable operational and territorial reports. | Yes. The district grouping needs RF21: without it, new reports have no district. | #42 | The district grouping comes with RF21. Planned: #59. |
| RF21 | **District and neighbourhood** from the location. | Stretch | #59 | Planned before the refactor: #59. |

### Low

| ID | Requirement | In the MVP | Issues | After the MVP |
| --- | --- | --- | --- | --- |
| RF15 | **Statistics** by category, territory, status and resolution time. | Partly. The report of #42 gives totals and resolution times per group; the dashboard beside the web map counts the incidents of the map by status, priority, category and department. Territory needs RF21. | #42, #177 | The staff dashboard is a requirement of the [final delivery](#-final-delivery), already built; territory comes with RF21 (Planned: #59), and a customizable dashboard is planned (Planned: #265). |
| RF16 | **Duplicates.** Suggest similar incidents by proximity, time and meaning. | No | — | The reports of several citizens unified into one normalized incident ([final delivery](#-final-delivery)). Planned: #333. |
| RF17 | **Zonal analysis.** Incidents and aggregated context for an area and period. | No | — | Not planned before the refactor: it serves the analyst's indicators and spatial patterns. |
| RF20 | **Intelligent assistance.** Classify, prioritize, summarize and recommend with a stated confidence. | No | — | No. Out of the final delivery by decision of the product owner (2026-11-20). |
| RF22 | **Urban asset.** Associate an incident with a nearby urban asset. | No | — | Urban assets near an incident, on the Málaga open data. Planned: #374, #350. |
| RF23 | **External context** from external sources. | Partly. The backend stores the weather of each new incident from Open-Meteo (ADR-0018, #307), staff read it through the API (#306) and see it in the *Context* section of the incident detail (#308). The traffic provider is only proposed (ADR-0024). | #304, #306, #307, #308 | A context service with its own database for the Málaga open data and the weather ([final delivery](#-final-delivery)). Planned: #346. Replaces the single schema of ADR-0010 for the context; the weather source of ADR-0018 is compared again in #372. |
| RF24 | **Contextual view.** The operator sees the urban context of an incident. | Partly. Operators, administrators and the assigned technician see the weather of the incident in the *Context* section of its detail (#308); traffic and assets are not built. | #304, #308 | The open data and the nearby assets in the view. Planned: #346, #374. |
| RF25 | **Historical correlation** of incidents with historical urban variables. | No | — | Not planned before the refactor: it serves the analyst's indicators and spatial patterns. |

The schema already has tables for some requirements outside the MVP (`urban_context`,
`urban_asset`, `notification`, `data_source_config`; see the [database guide](database.md)). A table
does not mean the requirement is implemented.

## 🎓 Final Delivery

The lecturers set requirements that the final delivery of the course must meet, on top of the case
study. They are mandatory, and some of them replace what the MVP left out or decided otherwise. This
section states them in the team's words; the course text stays outside the repository.

Each design proposal goes to the client in a design document: the lecturers validate it when the
team presents it in class, with the problems of each requirement and the possible solutions. The
design documents, their template and how they are validated are in [`docs/design`](design/README.md).

**Status** says what the code does today: *Built*, *Partly* (the note says what exists) or *No*. The
issue is the parent that covers the requirement; its sub-issues are listed in it.

| Requirement | Status | Issue | Replaces |
| --- | --- | --- | --- |
| **Staff dashboard.** Operators and technicians see the incidents on a map, general indicators and filters. | Built. The web map shows the incidents with markers coloured by status and sized by priority, clustered by zoom; beside it, a dashboard counts the same incidents in total, by priority, status, category and department, and lists the latest reported. Both use the inbox filters (status, category, date, priority, department) and a text search on the title and the description (#266). A technician sees only the incidents assigned to them. | #53, #58, #177; customizable: #265 | — |
| **Photos in an external service**, such as Cloudinary. | No. The photos are files in a local volume behind `StorageService`. | #341 | The local volume of ADR-0013 |
| **Normalized incidents.** The reports of several citizens about the same problem become one incident, and each report stays traceable to it: a report of a problem that already has an incident is attached to it instead of creating a duplicate, and the incident lists every citizen who reported it. Built in the core, before the services. | No. Each report is its own incident. | #333 | — |
| **Context service** that extracts, normalizes and manages external data (the chosen Málaga open data, and the weather from AEMET or another source) in its **own database**, kept up to date by periodic extraction or by asking the source when needed. | Partly. The `urbancontext` module of the core stores the weather of each new incident from Open-Meteo, in the shared schema; the open data is not loaded. | #346; the weather source: #372 | The single schema of ADR-0010 for the context; the weather provider of ADR-0018 if #372 chooses another |
| **Audit service** with its **own database**: every status of an incident, every change and who made it, with an analysis dashboard and a new **auditor** role that reads it with the administrators. The audit is the internal observability of the system (who changed what and why); its own database is a privacy measure. | Partly. The core's audit log records administrative actions and classifications, and administrators read it in a list; status changes are in each incident's history. There is no auditor role, dashboard or separate database. | #353 | The single schema of ADR-0010 for the audit |
| **Paginated bulk reads.** Every bulk REST read is paginated. | Partly. The incidents, the users, the audit entries, and the comments and classification changes of an incident are paginated ([API conventions](api.md#listings)); the map locations, the status changes of an incident and the technicians are not. The categories, the departments and the photos of an incident are short or bounded lists, not bulk reads, for the client to confirm in DD-001 (#412). | #360 | — |
| **Incident UUID.** A UUID identifies each incident across the services: it is its public id, a UUIDv7 for new incidents, while the keys inside the database are numeric. | Partly. The API identifies each incident by a UUID; new incidents do not get a UUIDv7 yet. | #369 | — |
| **Reliability and resilience** when a component (an external source or a service) is not available. | Partly. Creating an incident never waits for an external source, and the weather is fetched afterwards ([QA-2](architecture.md#qa-2-create-an-incident-while-an-external-source-is-down-rf26)); there are no services yet. | #327 | — |
| **Observability environment** that combines k6, Micrometer, structured logs, Prometheus and Grafana to evaluate the quality attributes. | Partly. k6, Micrometer, Prometheus and Grafana run for the backend (ADR-0023); the logs are plain text and not aggregated, and no service-level indicator is defined. | #362 | — |
| **Design documents validated by the client**, with the problems of each requirement and the possible solutions. | No. The folder, the template and the validation flow are in [`docs/design`](design/README.md); no design document is validated yet. | #328 and the design document of each parent above | — |

## 💻 Screens per Role

What each role gets, built or planned. How to use what is built, step by step, is in the
[user guide](user-guide.md).

Staff use the **web app** and citizens the **mobile app** (ADR-0005, ADR-0006). A citizen who signs
in on the web gets a notice to use the phone; staff who sign in on the phone get a notice to use the
web. Every screen is also protected by the API: hiding a menu entry is not access control
([security guide](security.md)).

The sign-in screen asks for the email and password. Staff accounts then type a code from an
authenticator app (#57, ADR-0021); the first time, the screen shows a QR code to enrol the app.

### Web: municipal operator

- **Menu:** *Inbox* and *Map*.
- **Inbox** (#46): incidents newest first, with their street address (#375) and the filters of
  #58 (status, category, date, priority, department, and district when #59 is done). The menu opens it filtered by `REPORTED`
  (#183).
- **Map** (#196): markers coloured by status and sized by priority, with the same filters as the
  inbox, clustered by zoom, each cluster labelled with its highest priority; refreshed every 15
  seconds. A click opens a summary with
  *Manage*, and the list of the incidents on the map, in its side column, opens the same summary
  from the keyboard. The menu opens it on
  every status that can still change. Beside it, a dashboard (#177) counts the same incidents: the
  total, by priority, charts by status, category and department, and the latest reported; each
  part folds and is remembered. The filters fold behind a button, with quick ranges of days. A
  customizable dashboard is planned in #265.
- **Incident detail** (#46): report data, the address with its source (#375), photos (#44), the
  weather when it was reported in the
  *Context* section (#308), and the history and internal comments (#50) in one timeline. The
  actions depend on the status (ADR-0020):

  | Status | Actions | Issue |
  | --- | --- | --- |
  | `REPORTED` | *Validate* (once the category and priority are saved with *Classify*), *Request information* (message the citizen sees), *Reject* (reason the citizen sees) | #47, #48 |
  | `VALIDATED`, `REOPENED` | *Assign* to a department, optionally a team and technician | #49 |
  | `ASSIGNED` | *Reassign* | #49 |
  | `RESOLVED` | *Verify and close*, *Reopen* (reason required) | #52 |
  | Any but `REJECTED` and `CLOSED` | *Classify* (category and priority with a justification) | #47 |
  | Any | *Comment* (internal, for the staff of the incident) | #50 |

- **Not in the MVP:** nearby urban assets, duplicate warnings and the traffic in the detail (RF16,
  RF22–RF24). After it, the reports of several citizens join one normalized incident instead of a
  duplicate warning (Planned: #333), and the detail shows the nearby assets (Planned: #374).

### Web: technician

- **Menu:** *Work queue* and *Map*.
- **Map** (#196): the operator's map with the incidents assigned to them, and its dashboard
  (#177) with their counts; the menu opens it on `ASSIGNED` and `IN_PROGRESS`, like the queue.
- **Work queue** (#51): the incidents assigned to them, with the filters and columns of the inbox
  (date reported, title, address, category, status and priority). It opens on `ASSIGNED` and `IN_PROGRESS`
  (#187).
- **Incident detail** (#51): report data, photos (#194), the weather in the *Context* section
  (#308) and the history, read-only. *Accept* (`ASSIGNED` → `IN_PROGRESS`), *Comment* (#50) and
  *Resolve* (`IN_PROGRESS` → `RESOLVED`, resolution notes required).

### Web: administrator

- **Menu:** *Users*, *Audit log* and *Reports*; an administrator can also do everything an
  operator can (ADR-0020), so they also get the operator's screens (#127), and the analyst's
  *Reports* (#42).
- **Users** (#40): list filtered by name, email and role; create a staff account with a role; edit
  the name and role; **deactivate** (not delete) an account; **reset the second factor** of a
  staff account with a reason (#57).
- **Audit log** (#41): read-only list filtered by date, actor and action; columns date, actor,
  action, target and details.
- **Not in the MVP:** the *Sources* screen for external data sources (it goes with RF23).

### Web: auditor

A new role of the [final delivery](#-final-delivery), not built yet.

- **Audit dashboard:** every change of every incident and who made it, analysed by period, actor
  and type, read-only; administrators see it too. Planned: #355 (the role), #358 (the dashboard).

### Web: analyst

- **Menu:** *Reports*.
- **Reports** (#42): indicators by status, category, priority, department or district for a period,
  shown on screen and downloadable as CSV. An analyst does not read individual incidents.

### Mobile app: citizen

- **Tabs:** *New*, *My reports* and *Profile*.
- **New** (#43, #198): title, description, optional category and a pin on the base map, which
  starts on the phone's position, or on Málaga without it, and the citizen moves to correct it. The
  accuracy is sent only while the pin is on the phone's position. After sending, optional photos
  from the camera or the gallery (#193).
- **My reports** (#45): the citizen's incidents filtered by open or closed; title, date and status.
  A photo thumbnail when there is one: Planned: #279.
- **Detail** (#45): the report, its position and the history the citizen may see (ADR-0020,
  *Citizen sees*): an information request shows as a message from the city council, and a rejection
  shows its reason. The photos: Planned: #279.
- **Profile:** name, email and *Sign out*.
- **Not in the MVP:** replying to an information request from the app (ADR-0020) and the
  notification channel settings (RF14).

## 🔀 Changes From the Original Prioritization

Each difference between the team's document of 2026-10-27 and the GitHub plan was checked against
the accepted decisions. In every case the original document was the side updated, so no issue
changed.

| Topic | Original document | Now | Decided by |
| --- | --- | --- | --- |
| Plan of Sprint 1 | Sprint 1 included user management, evidence, validation, classification, assignment, comments, filters and the technician's queue. | Sprint 1 was the walking skeleton (sign-up and sign-in, reporting, the citizen's list, the operator's inbox and detail); the rest is in Sprint 2. The milestones are the plan. | Milestones *Sprint 1* and *Sprint 2* |
| Audit log | `AuditLog` with the administrator's email as actor, the affected user's email as target, free text as details, and the actions `CREATE_USER`, `UPDATE_USER`, `DELETE_USER`. | `audit_log` keeps the actor's id, the target's type and id, and the values before and after as JSON, with no emails or names. Actions: `CREATE_USER`, `UPDATE_USER`, `CHANGE_ROLE`, `DISABLE_USER`, `ENABLE_USER`, `CHANGE_PASSWORD`, `CLASSIFY_INCIDENT`, `ENROLL_MFA`, `RESET_MFA`. Entries can never be changed or deleted. | [Security guide](security.md#-audit-trail), #36, #41 |
| Delete user | *Delete* with a confirmation. | *Deactivate*: the account can no longer sign in and its sessions are revoked. A user is referenced by incidents, history and the audit log, so it is never deleted. | #40, [security guide](security.md#-audit-trail) |
| API key of an external source | `DataSourceConfig.apiKey` stored in the database and typed in the *Sources* screen. | `data_source_config` has no key: secrets come from the environment (Twelve-Factor III). The *Sources* screen is not in the MVP. | ADR-0011, migration `V4` |
| Validate | *Validate* sets the category and priority and changes the status to `VALIDATED`. | Two steps that the same panel runs: classify (#47) and then validate (#48). Validation only checks that both are set. | ADR-0020, #47, #48 |
| Request information | A form to ask the citizen; the citizen answers from the app. | The incident stays `REPORTED` and the request is recorded in its history, visible to the citizen. The citizen's answer is not in the MVP. | ADR-0020 (option B), #48 |
| Resolve | Requires a text confirmation and/or a final photo. | Resolution notes are required; a final photo is not in the MVP. | ADR-0020, #51 |
| Reopen | Requires a comment or new evidence; the citizen can answer a reopened incident. | Only an operator reopens, with a required reason; new evidence does not replace it. The citizen does not interact with a reopened incident. | ADR-0020, #52 |
| Operator inbox | Prioritizes `REPORTED`, with filters by date, category, status, priority, district and department. | Opens filtered by `REPORTED`, and clearing the filters shows every incident; the district filter needs #59, planned before the refactor. | #46, #58, #183 |
| Notification channels | Push, email and SMS switches in the citizen's profile (RF14, Medium). | Not in the MVP: the delivery providers are only proposed. RF14 stays Medium. | ADR-0019 (proposed) |
| Operator detail | Urban context (traffic, weather), urban assets and duplicate warnings. | Only the weather enters the MVP, in the *Context* section of the detail (#308). Traffic, urban assets and duplicate warnings do not (RF16, RF22–RF24, Low). | ADR-0018, ADR-0024 (proposed), #304 |
| Menus | Administrator: *Users*, *Logs*, *Sources*. Operator: *Incident list*, *Incident map*. Technician: *My work queue*. Citizen: *New*, *My reports*, *Profile*. The analyst had none. | Administrator: *Users*, *Audit log*, plus the operator's screens. Operator: *Inbox*, *Map*. Technician: *Work queue*, *Map*. Analyst: *Reports*. Citizen: unchanged. | `frontend/apps/web/src/routing/sections.tsx`, #41, #42, #53, #127, #196 |
| Priority of RF18 (audit) | Low | High. `AGENTS.md` makes auditing status changes and administrative actions a domain rule, and Sprint 1 built the audit trail for it. | #36 |
| Priority of RF19 (reports) | Low | Medium. It is the analyst's only screen in the MVP. | #42 |

## 🔄 Keeping This Document Up to Date

- **At each sprint review**, the product owner checks the *In the MVP* and *After the MVP*
  columns against the milestones and updates them in a pull request.
- **When an issue changes the scope of a requirement** (part of it is moved out, or a stretch goal
  comes in), the same pull request updates the requirement's row.
- **When a requirement of the final delivery changes status**, the pull request that changes it
  updates its row in *Final Delivery*.
- **A new priority** is a team decision: record the old and the new value in *Changes From the
  Original Prioritization*, with the reason.
- **After the MVP** (from 2026-11-18), the requirements marked *No*, *Partly* or *Stretch* are
  prioritized for the next delivery and get their issues.
