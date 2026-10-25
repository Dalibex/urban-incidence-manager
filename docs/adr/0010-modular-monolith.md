# ADR-0010: Start with a modular monolith

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-25-10

## Context

The specification is explicit about the starting point: "Partiremos de un monolito sencillo con
tecnologías como Spring Boot y PostgreSQL y solo se incorporarán nuevos componentes cuando se
introduzca un problema observable, una hipótesis comprobable y una decisión arquitectónica que
pueda documentarse y evaluarse." The design guideline also states that new components are only
added for an observable problem with a testable hypothesis.

The monolith architecture the team is used to is Spring Boot plus a database, deployed as one unit and
organised **by layer** (`controller`, `service`, `repository`, `entity`). This is a widely used and
perfectly valid approach, but the separation is only a visual aid.

A distributed system (microservices) runs each service in its own process,
so communication, synchronisation and transactions all have to be handled explicitly. 

Which architectural style should the system adopt initially, and which modules does it have?

## Decision

We will build a **single deployable modular monolith**: one Spring Boot application
([ADR-0008](0008-backend.md)).

### Modules

Each module is a package `es.uma.urbanpulse.<module>` and a component in the C4 model
(`docs/architecture/workspace.dsl`). The backend is a single Spring Boot application split 
into the following modules.


| Module | Package | Responsibility 
|---|---|---|
| Identity | `identity` | Users, roles and permissions; sign-up, login, JWT and refresh tokens; Spring Security configuration applied to every request (ADR-0004); audit log. |
| Incidents | `incidents` | The incident as the central entity: reporting, location, categories, priority, lifecycle with allowed transitions, audited status history, assignments, comments and evidence metadata (binaries through `StorageService`, ADR-0013). |
| Urban context | `urbancontext` | External data: adapters for Open Data Málaga and OpenStreetMap (ADR-0009), Open-Meteo and TomTom (ADR-0018); loading of reference data, geocoding and asynchronous enrichment of incidents with timeouts and graceful degradation. |
| Geospatial | `geospatial` | Location and territory: districts, neighbourhoods, urban assets and spatial queries over local data (PostGIS, ADR-0003). |
| Notifications | `notifications` | Notifications derived from incident events and their delivery status; email through Brevo and SMS through Twilio, behind one sender per channel (ADR-0019). |
| Analytics | `analytics` | Read-only indicators, zone analysis and reports. |

The first version of this ADR listed a set of modules, and the C4 model listed a different set of
components. Code, ADR and C4 must use the same list so that each component in the diagram is 
a package in the code.

Note that assisted classification with an LLM is
**not a module yet**. It appears in the C4 model only as a **future** external system. It becomes a
module, and is added to this table, once and only once its own ADR is created and accepted if the 
project ends in that direction.

### Module structure
 Example for `incidents`:

  ```
  es.uma.urbanpulse.incidents
  ├── controller/IncidentController
  ├── dto/IncidentSummary  (record)
  ├── dto/IncidentRequest
  ├── entity/Incident, StatusChange, ...
  ├── mapper/IncidentMapper
  ├── repository/IncidentRepository
  └── service/DefaultIncidentService
  ```

- **No layer components.** Controllers, services and repositories are not components of their own; Each module owns its layers and keeps the usual MVC-style separation
  (controller / service / repository / entity / dto / mapper) inside its internal sub-packages.
- **One database, one schema**, owned by the application and shared by all modules.


## Considered Options

- **Modular monolith (package by feature)** — One deployable, real
  transactions, with implicit internal mainly to order the arquitecture.
- **Microservices from day one** — Independent scaling and deployment, but network calls replace
  transactions, every cross-cutting feature needs distributed coordination, and there is no
  observable problem yet that would justify it.

The modular monolith was selected because it satisfies the specification's own directive to
start simple. The team keeps working with the MVC-style layering it
already knows, now inside each module, so the cost of adoption is low.

## Consequences

- **Positives:**
  - One process to deploy, test and debug; local development is a single application is faster and simpler
    ([ADR-0011](0011-deployment-12factor.md)).
  - ACID transactions across incidents, assignments, status changes and audit records without
    distributed sagas.
  - The C4 component diagram and the package structure are the same thing, so the diagram has a
    purpose and there is a reason to keep it up to date.
  - Consistent with the design guideline of the specification, so no extra component is
    introduced without an observable problem.
- **Negatives:**
  - Shared database means one schema migration affects every module at once.
  - The whole system has to be running for the program to work; all or nothing.
  - Scaling becomes difficult in a larger project.
  - High coupling between modules.
  - If the code base grows, each package ends up with dozens of classes, making it hard to follow.

## Links Related

- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0009: Use opendata de Málaga as primary urban data source](0009-urban-data-source.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- C4 model: `docs/architecture/workspace.dsl`
