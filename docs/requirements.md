# 📋 Requirements and Prioritization

The priority of each functional requirement of the case study (RF01–RF26), which issues cover it in
the MVP, and the screens each role gets. It replaces the team's *Priorización de Requisitos* of
2026-10-27, which was written before the backlog was planned on GitHub; *Changes From the Original
Prioritization* below records where the two differed and which side was kept.

This document does not repeat the plan. The live plan is in GitHub:

- the **milestones** (*Sprint 1*, *Sprint 2*) say when each issue is done;
- the **issues** say what each feature includes and how it is accepted.

When the behaviour of a feature is decided elsewhere, that document wins and this one links to it:
the lifecycle in [ADR-0020](adr/0020-incident-lifecycle-transitions.md), permissions and the audit
trail in the [security guide](security.md), the API in the [OpenAPI contract](api/openapi.yml), and
any other decision in its [ADR](adr/).

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
or *No* (after the MVP). Whether an issue is finished is in GitHub, not here.

### High

| ID | Requirement | In the MVP | Issues |
| --- | --- | --- | --- |
| RF01 | **Identity and access.** Users sign up or sign in; staff authenticate. | Yes. A second factor for staff is planned with low priority. | #35, #39, #126, #165; #57 (second factor, ADR-0021) |
| RF02 | **Roles and permissions.** Different permissions for citizens, operators, technicians, administrators and analysts. | Yes | #35, #39, #100, #40; [permission matrix](security.md#permission-matrix) |
| RF03 | **Report an incident** with a title, description, position and optional category. | Yes | #43 |
| RF04 | **Location.** The incident keeps coordinates, normalized address and accuracy. | Partly. Coordinates and accuracy; correcting the position on a map is a stretch goal. The normalized address has no issue. | #43; #60 (stretch) |
| RF06 | **Consultation and tracking.** A user sees the detail, status, dates and visible history of their incidents. | Yes | #45 (citizen), #46 (staff), #100, #169 (timeline) |
| RF09 | **Validation.** An operator validates, requests more information or rejects a report with a reason. | Yes | #48 |
| RF11 | **Assignment** of a validated incident to a department, team or technician. | Yes | #49 (#184, #185) |
| RF12 | **Lifecycle.** Status changes follow the allowed transitions and are audited. | Yes | ADR-0020; #37, #99, #101, #102, #49, #51, #52 |
| RF18 | **Audit** of administrative actions, assisted decisions and relevant changes. | Partly. Administrative actions and classification; assisted decisions come with RF20. | #36, #40, #41, #47 |
| RF26 | **Controlled degradation.** An external source being down does not prevent creating an incident. | Yes. Creating an incident calls no external source; it has to stay that way when RF21–RF25 arrive. | #43 |

### Medium

| ID | Requirement | In the MVP | Issues |
| --- | --- | --- | --- |
| RF05 | **Evidence.** Photos or other files, with type and size validation. | Yes | #44 (ADR-0013; #192 API, #193 mobile, #194 web) |
| RF07 | **Search.** Staff filter by status, category, date, priority, district and department. | Yes. The district filter needs RF21. | #58 (#182 API, #183 web) |
| RF08 | **Map** of incidents and relevant urban layers. | Partly. Incidents on the web map, clustered by zoom and refreshed on their own; urban layers need urban data, which the MVP does not load. | #53 (#195 API, #196 web); #60 (stretch, mobile) |
| RF10 | **Priority**, manual or assisted, keeping its justification. | Partly. Manual priority with a required justification; assisted priority comes with RF20. | #47 |
| RF13 | **Collaboration.** Operators and technicians add comments, evidence and resolution data. | Yes. Every comment is internal in the MVP. | #50, #51, #44 |
| RF14 | **Notifications** of relevant changes through configurable channels. | No. The delivery providers are only proposed (ADR-0019). | — |
| RF19 | **Reports.** Exportable operational and territorial reports. | Yes. The district grouping needs RF21: without it, new reports have no district. | #42 |
| RF21 | **District and neighbourhood** from the location. | Stretch | #59 |

### Low

| ID | Requirement | In the MVP | Issues |
| --- | --- | --- | --- |
| RF15 | **Statistics** by category, territory, status and resolution time. | Partly. The report of #42 gives totals and resolution times per group; there is no dashboard. | #42 |
| RF16 | **Duplicates.** Suggest similar incidents by proximity, time and meaning. | No | — |
| RF17 | **Zonal analysis.** Incidents and aggregated context for an area and period. | No | — |
| RF20 | **Intelligent assistance.** Classify, prioritize, summarize and recommend with a stated confidence. | No | — |
| RF22 | **Urban asset.** Associate an incident with a nearby urban asset. | No | — |
| RF23 | **External context** from external sources. | No. The weather and traffic providers are only proposed (ADR-0018). | — |
| RF24 | **Contextual view.** The operator sees the urban context of an incident. | No | — |
| RF25 | **Historical correlation** of incidents with historical urban variables. | No | — |

The schema already has tables for some requirements outside the MVP (`urban_context`,
`urban_asset`, `notification`, `data_source_config`; see the [database guide](database.md)). A table
does not mean the requirement is implemented.

## 🖥️ Screens per Role

What each role gets, built or planned. How to use what is built, step by step, is in the
[user guide](user-guide.md).

Staff use the **web app** and citizens the **mobile app** (ADR-0005, ADR-0006). A citizen who signs
in on the web gets a notice to use the phone; staff who sign in on the phone get a notice to use the
web. Every screen is also protected by the API: hiding a menu entry is not access control
([security guide](security.md)).

The sign-in screen asks for the email and password; a staff account with a second factor also asks
for its code (#57).

### Web: municipal operator

- **Menu:** *Inbox* and *Map*.
- **Inbox** (#46): incidents newest first, with the filters of #58 (status, category, date,
  priority, department, and district when #59 is done). The menu opens it filtered by `REPORTED`
  (#183).
- **Map** (#196): markers coloured by status and sized by priority, with the same filters as the
  inbox, clustered by zoom, each cluster labelled with its highest priority; refreshed every 15
  seconds. A click opens a summary with
  *Manage*, and a list under the map opens the same summary from the keyboard. The menu opens it on
  every status that can still change.
- **Incident detail** (#46): report data, photos (#44), history and internal comments (#50) in one
  timeline. The actions depend on the status (ADR-0020):

  | Status | Actions | Issue |
  | --- | --- | --- |
  | `REPORTED` | *Validate* (sets the category and priority first if they are missing), *Request information*, *Reject* (reason the citizen sees) | #47, #48 |
  | `VALIDATED`, `REOPENED` | *Assign* to a department, optionally a team and technician | #49 |
  | `ASSIGNED` | *Reassign* | #49 |
  | `RESOLVED` | *Verify and close*, *Reopen* (reason required) | #52 |
  | Any but `REJECTED` and `CLOSED` | *Classify* (category and priority with a justification) | #47 |
  | Any | *Comment* (internal, for the staff of the incident) | #50 |

- **Not in the MVP:** urban context, nearby urban assets and duplicate warnings in the detail
  (RF16, RF22–RF24).

### Web: technician

- **Menu:** *Work queue* and *Map*.
- **Map** (#196): the operator's map with the incidents assigned to them; the menu opens it on
  `ASSIGNED` and `IN_PROGRESS`, like the queue.
- **Work queue** (#51): the incidents assigned to them, with the filters and columns of the inbox
  (date reported, title, category, status and priority). It opens on `ASSIGNED` and `IN_PROGRESS`
  (#187).
- **Incident detail** (#51): report data, photos (#194) and the history, read-only. *Accept*
  (`ASSIGNED` → `IN_PROGRESS`), *Comment* (#50) and *Resolve* (`IN_PROGRESS` → `RESOLVED`,
  resolution notes required).

### Web: administrator

- **Menu:** *Users*, *Audit log* and *Reports*; an administrator can also do everything an
  operator can (ADR-0020), so they also get the operator's screens (#127), and the analyst's
  *Reports* (#42).
- **Users** (#40): list filtered by name, email and role; create a staff account with a role; edit
  the name and role; **deactivate** (not delete) an account.
- **Audit log** (#41): read-only list filtered by date, actor and action; columns date, actor,
  action, target and details.
- **Not in the MVP:** the *Sources* screen for external data sources (it goes with RF23).

### Web: analyst

- **Menu:** *Reports*.
- **Reports** (#42): indicators by status, category, priority, department or district for a period,
  shown on screen and downloadable as CSV. An analyst does not read individual incidents.

### Mobile app: citizen

- **Tabs:** *New*, *My reports* and *Profile*.
- **New** (#43): title, description, optional category and the phone's position with its
  accuracy. After sending, optional photos from the camera or the gallery (#193). Choosing or correcting the position on a map is a stretch goal (#60).
- **My reports** (#45): the citizen's incidents filtered by open or closed; title, date, status and
  a photo thumbnail when there is one.
- **Detail** (#45): the report, its position, photos and the history the citizen may see (ADR-0020,
  *Citizen sees*): an information request shows as a message from the city council, and a rejection
  shows its reason.
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
| Audit log | `AuditLog` with the administrator's email as actor, the affected user's email as target, free text as details, and the actions `CREATE_USER`, `UPDATE_USER`, `DELETE_USER`. | `audit_log` keeps the actor's id, the target's type and id, and the values before and after as JSON, with no emails or names. Actions: `CREATE_USER`, `UPDATE_USER`, `CHANGE_ROLE`, `DISABLE_USER`, `ENABLE_USER`, `CLASSIFY_INCIDENT`. Entries can never be changed or deleted. | [Security guide](security.md#audit-trail), #36, #41 |
| Delete user | *Delete* with a confirmation. | *Deactivate*: the account can no longer sign in and its sessions are revoked. A user is referenced by incidents, history and the audit log, so it is never deleted. | #40, [security guide](security.md#audit-trail) |
| API key of an external source | `DataSourceConfig.apiKey` stored in the database and typed in the *Sources* screen. | `data_source_config` has no key: secrets come from the environment (Twelve-Factor III). The *Sources* screen is not in the MVP. | ADR-0011, migration `V4` |
| Validate | *Validate* sets the category and priority and changes the status to `VALIDATED`. | Two steps that the same panel runs: classify (#47) and then validate (#48). Validation only checks that both are set. | ADR-0020, #47, #48 |
| Request information | A form to ask the citizen; the citizen answers from the app. | The incident stays `REPORTED` and the request is recorded in its history, visible to the citizen. The citizen's answer is not in the MVP. | ADR-0020 (option B), #48 |
| Resolve | Requires a text confirmation and/or a final photo. | Resolution notes are required; a final photo is not in the MVP. | ADR-0020, #51 |
| Reopen | Requires a comment or new evidence; the citizen can answer a reopened incident. | Only an operator reopens, with a required reason; new evidence does not replace it. The citizen does not interact with a reopened incident. | ADR-0020, #52 |
| Operator inbox | Prioritizes `REPORTED`, with filters by date, category, status, priority, district and department. | Opens filtered by `REPORTED`, and clearing the filters shows every incident; the district filter needs #59 (stretch). | #46, #58, #183 |
| Notification channels | Push, email and SMS switches in the citizen's profile (RF14, Medium). | Not in the MVP: the delivery providers are only proposed. RF14 stays Medium. | ADR-0019 (proposed) |
| Operator detail | Urban context (traffic, weather), urban assets and duplicate warnings. | Not in the MVP (RF16, RF22–RF24, Low). | ADR-0018 (proposed) |
| Menus | Administrator: *Users*, *Logs*, *Sources*. Operator: *Incident list*, *Incident map*. Technician: *My work queue*. Citizen: *New*, *My reports*, *Profile*. The analyst had none. | Administrator: *Users*, *Audit log*, plus the operator's screens. Operator: *Inbox*, *Map*. Technician: *Work queue*, *Map*. Analyst: *Reports*. Citizen: unchanged. | `frontend/apps/web/src/routing/sections.tsx`, #41, #42, #53, #127, #196 |
| Priority of RF18 (audit) | Low | High. `AGENTS.md` makes auditing status changes and administrative actions a domain rule, and Sprint 1 built the audit trail for it. | #36 |
| Priority of RF19 (reports) | Low | Medium. It is the analyst's only screen in the MVP. | #42 |

## 🔄 Keeping This Document Up to Date

- **At each sprint review**, the product owner checks the *In the MVP* column against the
  milestones and updates it in a pull request.
- **When an issue changes the scope of a requirement** (part of it is moved out, or a stretch goal
  comes in), the same pull request updates the requirement's row.
- **A new priority** is a team decision: record the old and the new value in *Changes From the
  Original Prioritization*, with the reason.
- **After the MVP** (from 2026-11-18), the requirements marked *No*, *Partly* or *Stretch* are
  prioritized for the next delivery and get their issues.
