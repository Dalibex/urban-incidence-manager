# ADR-0004: Authentication strategy

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-09-29

## Context

The system has user accounts, and some incidents or evidences must be visible only to their author
or to the moderation team. Both clients (web and mobile) need to authenticate against the same API,
and access must be revocable when an account is disabled or a session is compromised.

How should authentication be handled?

## Decision

We will use **self-managed authentication with short-lived JWT access tokens and rotating refresh
tokens** issued by our own API.

- **Access token:** signed JWT, short expiry (about 15 minutes), sent in the `Authorization` header.
- **Refresh token:** long-lived opaque token stored hashed in PostgreSQL, rotated on every refresh,
  and revoked server-side on logout, password change or account deactivation.
- **Credentials:** email and password hashed with a slow password hashing algorithm (Argon2 or
  bcrypt); login is rate limited.
- **Authorization:** role checks in the API (`user`, `moderator`, `admin`); the client only hides
  UI that the user cannot use, it never enforces permissions.
- **Social login** (Google, Apple, GitHub) can be added later as a registration path without
  changing the token model.

## Considered Options

- **JWT access tokens + rotating refresh tokens** — Stateless requests, standard and supported by
  most tooling. Selected.
- **Server-side sessions with cookies** — Simple and revocable, but cookies are harder to use from a
  mobile client (secure storage, cookie handling across WebViews and third-party cookies policies).
- **Third-party identity provider (Auth0, Firebase Auth, Keycloak)** — Less code and built-in
  social login, but adds a vendor dependency, cost and an external system in the authentication path.
- **OAuth2/OIDC with a full authorization server** — Best for a public API consumed by third parties,
  more complexity than needed at this stage.

## Consequences

- **Positives:**
  - The same token model works for web and mobile without relying on browser cookie behaviour.
  - Sessions can be revoked server-side, and every session is auditable.
  - No external identity vendor, no additional cost and no data leaving our system.
  - Social login can be added later without migrating existing users.
- **Negatives:**
  - We own credential storage, token rotation, password reset and email delivery.
  - Access tokens cannot be revoked instantly; the short expiry is what limits the exposure window.
  - Token refresh and storage must be implemented carefully in both clients to avoid losing the
    session or leaking tokens.

## Links Related

- [ADR-0001: Use PostgreSQL for primary database](0001-database.md)
- [ADR-0005: Use React Native for the mobile application](0005-mobile-application-technology.md)
- [ADR-0006: Use React for the web application](0006-frontend-web-technology.md)
