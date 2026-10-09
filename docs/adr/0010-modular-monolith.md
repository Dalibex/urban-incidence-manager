# ADR-0010: Start with a modular monolith

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-06

## Context

The specification is explicit about the starting point: "Partiremos de un monolito sencillo con
tecnologías como Spring Boot y PostgreSQL y solo se incorporarán nuevos componentes cuando se
introduzca un problema observable, una hipótesis comprobable y una decisión arquitectónica que
pueda documentarse y evaluarse." The design guideline also states that new components are only
added for an observable problem with a testable hypothesis.

The system covers identity and roles, the full incident lifecycle, a map, statistics, external
data ingestion, notifications and AI assistance. The team must deliver this inside one semester,
and atomic writes across incidents, assignments, status changes and audit records are a core
requirement of the domain model.

The first version of this ADR listed six modules, and the C4 model listed a different set of
components (including a generic "API REST Controllers" component). Code, ADR and C4 must use the
same list so that each component in the diagram is a package in the code.

Which architectural style should the system adopt initially, and which modules does it have?

## Decision

We will build a **single deployable modular monolith**: one Spring Boot application
([ADR-0008](0008-backend.md)) with explicitly bounded internal modules.

- **Modules.** Each module is a package `es.uma.urbanpulse.<module>` and a component in the C4
  model (`docs/architecture/workspace.dsl`):

- **No layer components.** Controllers, services and repositories are not components of their own;
  each module owns its layers.
- **One database, one schema**, owned by the application; each module owns its tables and other
  modules reach that data only through the module's public API, never through cross-module joins
  or JPA relationships.
- **No network calls between modules**: everything is an in-process method call or an in-process
  domain event, so a single transaction can cover incident creation plus audit plus status change.
- **Enforcement:** Spring Modulith verifies the module structure on every build
  (`ApplicationModules.of(...).verify()`), so boundaries cannot erode silently.
- **Trigger for extracting a component:** a module is only split out of the monolith when there
  is an observable problem, a testable hypothesis and a new ADR, following the design guideline
  of the specification. No extraction is planned now.

## Considered Options

- **Modular monolith** — One deployable, real transactions, explicit internal boundaries.
- **Microservices from day one** — Independent scaling and deployment, but network calls replace
  transactions, every cross-cutting feature needs distributed coordination, and there is no
  observable problem yet that would justify it.
- **simple monolith (no internal modules)** — The fastest start, but with seven functional
  areas the codebase becomes entangled and the boundaries that make later extraction possible
  never exist.

The modular monolith was selected because it satisfies the specification's own directive to
start simple while keeping the internal boundaries that allow a component to be extracted later
when, and only when, a real problem appears.

## Consequences

- **Positives:**
  - One process to deploy, test and debug; local development is a single application
    ([ADR-0011](0011-deployment-12factor.md)).
  - ACID transactions across incidents, assignments, status changes and audit records without
    distributed sagas.
  - The C4 component diagram and the package structure are the same thing, so the diagram stays
    true and can be generated from code.
  - Module boundaries make the code testable per module and keep extraction options open.
  - Consistent with the design guideline of the specification, so no extra component is
    introduced without an observable problem.
- **Negatives:**
  - Failure isolation does not exist: a bug in one module can take down the whole process.
  - Scaling is vertical for the application as a whole; a single hot module cannot be scaled
    independently.
  - Boundaries are verified by tests, which only protect what the build runs; a disabled test
    removes the protection.
  - Shared database means one schema migration affects every module at once.

## Links Related

- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0009: Use opendata de Málaga as primary urban data source](0009-urban-data-source.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- C4 model: `docs/architecture/workspace.dsl` (component view "Componentes")
