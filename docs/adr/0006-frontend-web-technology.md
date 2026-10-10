# ADR-0006: Use React for the web application

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

Moderators and analysts need to review incidents, filter them by area, inspect evidences on a map
and manage users. This is an internal web tool used mostly on desktop, so it must be easy to build,
fast to iterate on, and able to render dense lists of incidents next to a map.

Which technology should be used for the web client?

## Decision

We will use **React with TypeScript**, bundled with **Vite**.

- **Component model** that fits the screens of the tool: map, filters, incident list and detail
  views.
- **Vite** for fast development server, simple configuration and a production build with code
  splitting per route.
- **React Router** for routing and URL-driven filters (area, date, status), so views are shareable
  and bookmarkable.
- **React Query** for server state: caching, retries and invalidation of incidents and evidences.
- **Map rendering** with `MapLibre` and OSM tiles (see [ADR-0002](0002-open-street-map.md)).
- **Styling** with CSS Modules, avoiding a component library that would impose its own design
  system.

## Considered Options

- **React + Vite + TypeScript** — Small, flexible and aligned with the mobile stack. Selected.
- **Next.js** — SSR and routing out of the box, but most of this tool is behind authentication and
  client side; the extra server complexity is not justified at this stage.
- **Angular** — Strong structure and tooling, but heavier for this size and a different paradigm
  for the team.

## Consequences

- **Positives:**
  - Same language and ecosystem as the mobile app, so components and hooks can be reused.
  - Fast feedback loop during development and small production bundles.
  - URL-driven state makes filtered views shareable and easy to test.
  - Library support for maps and data fetching is mature.
- **Negatives:**
  - Everything runs in the browser, so SEO and first paint matter less but initial load depends on
    JavaScript size; code splitting per route mitigates it.
  - Client side state management and caching must be handled deliberately (React Query) to avoid
    inconsistent views.
  - No server rendering, so the tool cannot be indexed or shared publicly as static content.

## Links Related

- [ADR-0002: Use OpenStreetMap for map data](0002-open-street-map.md)
- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0005: Use React Native for the mobile application](0005-mobile-application-technology.md)
- [ADR-0007: Client application architecture](0007-client-application-architecture.md)
- [Vite](https://vite.dev/)
