# ADR-0007: Client application architecture

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-08

## Context

We maintain two clients, web and mobile, that consume the same API and share the same domain:
incidents, evidences and users. Duplicating that logic in two codebases would lead to divergent
behaviour, while putting everything in the UI layers would make it impossible to reuse or test.

How should the client code be organized to share what is common?

## Decision

We will use a **monorepo with two applications and shared packages**.

- **Structure:** `apps/web` and `apps/mobile` (see [ADR-0005](0005-mobile-application-technology.md)
  and [ADR-0006](0006-frontend-web-technology.md)), plus `packages/*` for the shared code.
- **Shared packages:**

  - `domain`: entities, enums, business rules and validation schemas.
  - `api-client`: typed HTTP client, DTOs, error handling and token refresh.
  - `ui`: design tokens and platform-agnostic pieces (icons, formatting, empty states).
- **Navigation:** React Router in web and React Navigation in mobile, kept inside each app.
- **Platform-specific code** (camera, GPS, secure storage, map components) lives in the apps and
  is accessed through small interfaces defined in the shared packages.
- **Tooling:** pnpm workspaces and TypeScript project references, so shared packages are typed and
  consumed as source.

## Considered Options

- **Monorepo with shared packages** — Single repository, explicit reuse, typed contracts. Selected.
- **A shared UI component library only** — Reduces visual duplication, but business logic and the API
  client would still be duplicated.

## Consequences

- **Positives:**
  - Domain logic and the API client exist once, with a single set of tests.
  - A type change in a shared package surfaces as a compilation error in both clients.
  - Fixes and features reach both platforms at the same time.
  - Each app can still use its own navigation and platform components.
- **Negatives:**
  - Shared packages must stay platform agnostic; a change that touches UI in one platform may still
    require per-app work.
  - Tooling (workspaces, TypeScript, test runners, ESLint) must be configured once and maintained
    centrally.
  - Pull requests that touch shared packages affect both apps, so review and release coordination
    matter.
  - Mobile and web dependencies can drift, since each app resolves its own version.

## Links Related

- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0005: Use React Native for the mobile application](0005-mobile-application-technology.md)
- [ADR-0006: Use React for the web application](0006-frontend-web-technology.md)
