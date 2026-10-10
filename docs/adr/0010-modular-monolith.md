# ADR-0010: Start with a modular monolith

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-10

## Context

The specification is explicit about the starting point: "Partiremos de un monolito sencillo con
tecnologías como Spring Boot y PostgreSQL y solo se incorporarán nuevos componentes cuando se
introduzca un problema observable, una hipótesis comprobable y una decisión arquitectónica que
pueda documentarse y evaluarse." The design guideline also states that new components are only
added for an observable problem with a testable hypothesis.

The "simple monolith" the team is used to is Spring Boot plus a database, deployed as one unit and
organised **by layer** (`controller`, `service`, `repository`, `entity`). This is a widely used and
perfectly valid approach, but the separation is only a visual aid: everything is `public`,
implementing one feature crosses several layer packages, and the result is high coupling between
layers and low cohesion. If the code base grows, each package ends up with dozens of classes, and
removing all packages would not change how the system behaves. Nothing in the build protects the
structure.

At the other extreme, a distributed system (microservices) runs each service in its own process,
so communication, synchronisation and transactions all have to be handled explicitly. That cost is
disproportionate for the size of UrbanPulse.

The first version of this ADR listed six modules, and the C4 model listed a different set of
components (including a generic "API REST Controllers" component). Code, ADR and C4 must use the
same list so that each component in the diagram is a package in the code.

Which architectural style should the system adopt initially, and which modules does it have?

## Decision

We will build a **single deployable modular monolith**: one Spring Boot application
([ADR-0008](0008-backend.md)) with explicitly bounded internal modules.

### Modules

Each module is a package `es.uma.urbanpulse.<module>` and a component in the C4 model
(`docs/architecture/workspace.dsl`). In code, a module is a package annotated with
`@ApplicationModule` in its `package-info.java`.

### Module structure

- **Public API vs internal.** Only the root package of a module is visible to other modules: its
  public service interfaces (the module's "contract"), the records they return, and its domain
  events. Every sub-package is **internal** and can only be used from inside the module. Example for
  `incidents`:

  ```
  es.uma.urbanpulse.incidents
  ├── IncidentService          (interface)  ┐
  ├── IncidentSummary          (record)     ├─ public API
  ├── IncidentCreated          (event)      ┘
  ├── controller/IncidentController
  ├── dto/IncidentResponse
  ├── entity/Incident, StatusChange
  ├── mapper/IncidentMapper
  ├── repository/IncidentRepository
  └── service/DefaultIncidentService   (implements IncidentService)
  ```

- **No layer components.** Controllers, services and repositories are not components of their own; Each module owns its layers and keeps the usual MVC-style separation
  (controller / service / repository / entity / dto / mapper) inside its internal sub-packages.
- **One database, one schema**, owned by the application; each module owns its tables.
- **No cross-module entity relationships.** Within a module, normal JPA relationships are fine
  (e.g. `Incident` → `StatusChange` as `@OneToMany`). Between modules, an entity only stores the
  other module's **id** (e.g. `reporterId`, `districtId`), never `@ManyToOne`/`@OneToMany` to
  another module's entity, and there are no cross-module joins. Data from another module is
  requested through that module's public API; for lists, the API offers a batch method
  (e.g. `findByIds(Set<UUID>)`) so one call serves all ids.
- **No network calls between modules**: everything is an in-process method call or an in-process
  domain event.

### Communication between modules

| Mechanism | Use when | Example |
|---|---|---|
| **Direct call** to the other module's public interface | The caller needs the answer to continue. This is the default. | `analytics` asks `incidents` for the open incidents of a district. |
| **Dependency inversion** | The caller needs something from another module but must not depend on it (it would create a cycle, or the central module would depend on an auxiliary one). The needing module declares the interface in its own API; the other module implements it. | `incidents` declares `LocationResolver`; `geospatial` implements it. |
| **Domain event** (publish and listen) | The publisher only announces that something happened and needs no answer. | `IncidentCreated` is consumed by `externaldata` (enrichment) and `notifications`. |

#### Decision rule: 

(1) Do I need the response to continue? No → event. 

(2) If yes, would depending on
that module create a cycle or a dependency that should not exist? No → direct call; yes →
dependency inversion.

Consequences of the event mechanism that must be respected:

- Listeners (`@ApplicationModuleListener`) run **after the publisher's transaction commits**. If a
  listener fails, the incident is already saved.
- Therefore, **anything that must be atomic with the incident write (incident creation, status
  change, assignment, audit record) must be done through direct in-process calls inside the same
  transaction, not through events.** Events are for side effects that may happen afterwards
  (enrichment, notifications, statistics).

### Enforcement and documentation

- **Boundary verification.** `ModularityTest`
  (`src/test/java/es/uma/urbanpulse/ModularityTest.java`) calls
  `ApplicationModules.of(UrbanpulseApplication.class).verify()` and runs in the repository's CI. It
  fails the build if a module uses another module's internal types or if there are dependency
  cycles between modules.
- **Generated documentation.** `DocumentationTest` uses Spring Modulith's `Documenter` to write
  module documentation to `target/spring-modulith-docs` (generated, not versioned): `components.puml`
  (component diagram with all modules), one `module-<name>.puml` and one `module-<name>.adoc` (module
  canvas) per module, and an `all-docs.adoc` index. This shows what is actually in the code and is
  used to check that the hand-maintained C4 model (`workspace.dsl`) still matches it.
- **Observability (optional).** Spring Boot metrics (per-endpoint request count, total time and
  maximum time) can be scraped by Prometheus and plotted in Grafana. This is available through the
  Spring Boot configuration (`pom.xml`, `application.yml`) but is not a goal of this decision and no
  extra component is introduced for it.

## Considered Options

- **Modular monolith (package by feature, verified with Spring Modulith)** — One deployable, real
  transactions, explicit internal boundaries that are enforced by the build.
- **Microservices from day one** — Independent scaling and deployment, but network calls replace
  transactions, every cross-cutting feature needs distributed coordination, and there is no
  observable problem yet that would justify it.
- **simple monolith (no internal modules)** — The fastest and most familiar
  start, but with seven functional areas the packages fill up with unrelated classes, coupling
  between layers is high and cohesion low, nothing is enforced (everything is public), and the
  boundaries that make later extraction possible never exist.

The modular monolith was selected because it satisfies the specification's own directive to
start simple while keeping the internal boundaries that allow a component to be extracted later
when, and only when, a real problem appears. The team keeps working with the MVC-style layering it
already knows, now inside each module, so the cost of adoption is low.

## Consequences

- **Positives:**
  - One process to deploy, test and debug; local development is a single application
    ([ADR-0011](0011-deployment-12factor.md)).
  - ACID transactions across incidents, assignments, status changes and audit records without
    distributed sagas.
  - High cohesion and low coupling between modules: a change stays inside its module, and the
    boundaries are real rules, not only a visual separation.
  - The C4 component diagram and the package structure are the same thing, so the diagram has a
    purpose and there is a reason to keep it up to date; Spring Modulith generates diagrams of
    what the code actually contains.
  - Module boundaries make the code testable per module and keep extraction options open; if a
    module's public API is respected, moving it out later is easier than untangling a layered
    code base.
  - Consistent with the design guideline of the specification, so no extra component is
    introduced without an observable problem.
- **Negatives:**
  - Failure isolation does not exist: a bug in one module can take down the whole process.
  - Scaling is vertical for the application as a whole; a single hot module cannot be scaled
    independently (this only becomes possible after an extraction, which requires its own ADR).
  - Boundaries are verified by tests, which only protect what the build runs; a disabled test
    removes the protection.
  - Shared database means one schema migration affects every module at once.
  - Extra ceremony: every module needs a public interface and public records, and the team must
    learn the conventions (internal packages, id-only references, events).
  - No cross-module joins: reading data owned by another module needs an API call (batch methods
    to avoid one call per row), and cross-module reports are less convenient than a SQL join.
  - Events run after commit, so their effects are eventually consistent and must not be used for
    anything that has to be atomic with the main write.
  - Adds a dependency on Spring Modulith.

## Links Related

- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0009: Use opendata de Málaga as primary urban data source](0009-urban-data-source.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- C4 model: `docs/architecture/workspace.dsl` (component view "Componentes")
- Spring Modulith: <https://spring.io/projects/spring-modulith>
  ([fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html#modules),
  [application events](https://docs.spring.io/spring-modulith/reference/events.html))
