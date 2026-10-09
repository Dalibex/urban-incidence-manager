# ADR-0005: Use React Native for the mobile application

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

Users need to report incidents, attach evidences with their location and consult the map while
moving, so a mobile application is required. It must support the device camera, GPS, offline
drafts and map interaction, and it should reuse as much business logic as possible with the web
application.

Which technology should be used for the mobile client?

## Decision

We will use **React Native with Expo** and **TypeScript** for the mobile application.

- **Shared language and ecosystem with the web app:** components, hooks and business logic are
  reusable, so the team maintains one way of thinking about the product (see [ADR-0007](0007-client-application-architecture.md)).
- **Native capabilities through Expo:** camera, location, file system and secure token storage are
  available as maintained modules.
- **Map support:** `MapLibre` covers rendering OSM-based tiles and markers on both platforms.
- **Managed workflow with EAS (Expo Application Services) Build and EAS Update** for signing and distributing updates, which
  avoids maintaining native build infrastructure.
- **Single codebase for iOS and Android**, reducing duplicated effort.

## Considered Options

- **React Native with Expo** — Shared stack with web, native modules, managed builds. Selected.
- **Native Android (Kotlin) and iOS (Swift)** — Best platform integration and performance, but two
  codebases, two teams-worth of knowledge and no code sharing with the web app.
- **Flutter (Dart)** — Consistent UI across platforms and good performance, but a different language
  and ecosystem from the web app, so nothing is shared.

## Consequences

- **Positives:**

  - Business logic, API client and design tokens are shared with the web application.
  - One team and one language (TypeScript) can maintain both clients.
  - EAS Build and EAS Update remove most of the native toolchain and distribution overhead.
  - Access to device capabilities and store distribution through Expo modules.
- **Negatives:**

  - The app depends on the React Native and Expo ecosystem. Vendor lock-in.
  - Expo services (builds, updates) may be paid depending on the plan.

## Links Related

- [ADR-0002: Use OpenStreetMap for map data](0002-open-street-map.md)
- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0006: Use React for the web application](0006-frontend-web-technology.md)
- [ADR-0007: Client application architecture](0007-client-application-architecture.md)
