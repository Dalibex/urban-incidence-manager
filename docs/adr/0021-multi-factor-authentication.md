# ADR-0021: Require a TOTP second factor for staff accounts

- **Status:** Proposed
- **Decision Makers:** Project Team
- **Date:** 2026-10-27

## Context

Staff accounts (`MUNICIPAL_OPERATOR`, `TECHNICIAN`, `ADMINISTRATOR`, `ANALYST`) can read the
personal data of citizens, such as the position and photos of their reports, and administrators can
create accounts and change roles (RF01, RF02, RF18). With the model of
[ADR-0004](0004-authentication-strategy.md), a password is the only barrier: a leaked or reused
password gives full access to all of that. ADR-0004 decided the token model, not a second factor.

- Staff work only in the web app ([ADR-0006](0006-frontend-web-technology.md)). Citizens use only
  the mobile app ([ADR-0005](0005-mobile-application-technology.md)) and see only their own
  incidents. A staff member who wants to report an incident does it with a separate citizen account.
- Both apps call the same API. The API cannot trust a client to say which app it is, because any
  caller can send the same requests.
- No email or SMS provider is available: [ADR-0019](0019-notification-delivery-providers.md) is
  only Proposed.
- The demo must stay at zero cost ([ADR-0011](0011-deployment-12factor.md)).

How do we stop a stolen staff password from being enough to sign in, without a paid provider?

## Decision

We will require a **time-based one-time password (TOTP, RFC 6238)** from an authenticator app for
every staff role. Citizens do not need it in this version.

- **Enforced by role in the API, whatever the client.** The API asks for the second factor when the
  account has a role listed in `MFA_REQUIRED_ROLES` (by default, all four staff roles). Since staff
  only use the web app, in practice the second factor only appears there. The mobile app has no
  code step, so a staff account cannot sign in from it.
- **Two-step sign-in.** A correct password for such an account returns a short-lived, single-use
  challenge and **no tokens**. The access and refresh tokens of ADR-0004 are issued only after a
  valid code is sent for that challenge. The token model itself does not change.
- **Enrolment.** On the first sign-in after the second factor becomes required, the user scans a QR
  code (`otpauth://` URI) and confirms it with a first valid code.
- **Codes.** A code is valid for its 30-second time step and one step on each side. A code that was
  already used is rejected, and failed attempts are rate limited, as the password step already is.
- **Secret storage.** The TOTP secret is stored encrypted at rest, with a key read from
  `MFA_ENCRYPTION_KEY`. Following twelve-factor (factor III), the key and the list of roles come
  from the environment and are never committed.
- **Lost device.** An administrator resets the user's second factor. The reset is audited and
  revokes the user's refresh tokens, and the user enrols again on the next sign-in.
- **Last administrator.** If no other administrator can do the reset, the maintainer runs a
  documented emergency procedure on the database that clears the user's second factor and records
  who did it and why. Recovery codes are left for later.

## Considered Options

- **TOTP with an authenticator app** — No provider, no cost, works offline and is a standard. The
  user needs an app, and a lost phone needs a reset. Selected.
- **One-time code by email** — Familiar for users, but it needs an email provider (ADR-0019 is
  only Proposed) and depends on its availability.
- **Code by SMS** — Familiar, but it costs money per message, is exposed to SIM swapping and needs a
  provider.
- **WebAuthn / passkeys** — The strongest option and resistant to phishing, but much more complex
  (browser APIs, attestation) and hard to test in CI.
- **Password only** — Nothing to build, but one leaked password exposes personal data and the
  administration of the platform.

TOTP is the only option that raises the bar without an external provider or a cost, which is what
the project can afford now.

## Consequences

- **Positives:**
  - A leaked staff password is no longer enough to read citizens' data or change roles.
  - No provider, no cost and no new service.
  - The rule lives in the API, so a caller cannot skip it by pretending to be the mobile app.
- **Negatives:**
  - Staff need an authenticator app, and the demo has to include the enrolment.
  - One more secret to manage. If `MFA_ENCRYPTION_KEY` is lost, every staff user has to enrol again.
  - A lost phone needs an administrator, and the last administrator needs the emergency procedure.
  - Staff accounts cannot be used from the mobile app.
- **Implementation (#57):**
  - A new Flyway migration in `identity` for the encrypted secret, the enabled flag and the last used
    time step. `V2__identity_create_users_tables.sql` is not edited.
  - `MFA_ENCRYPTION_KEY` becomes a mandatory property, so the `build` smoke test must pass it as an
    environment variable ([CI guide](../ci.md)).
  - Tests use a controllable clock, since codes depend on the time.
  - The TOTP implementation comes from a maintained library, checked before it is added as
    `AGENTS.md` asks for any new dependency.
  - No new C4 container; at most a component inside the `identity` module.

## How it will be evaluated

- **Metric:** an integration test that signs in with every role.
- **Threshold:** 0 tokens issued to a role in `MFA_REQUIRED_ROLES` without a valid code, and citizen
  sign-in unchanged.
- **Review:** at the end of Sprint 2, once #57 is merged. Revisit for recovery codes if resets
  become frequent, or for WebAuthn if phishing becomes the main risk.

## Links Related

- [ADR-0004: Authentication strategy](0004-authentication-strategy.md)
- [ADR-0005: Use React Native for the mobile application](0005-mobile-application-technology.md)
- [ADR-0006: Use React for the web application](0006-frontend-web-technology.md)
- [ADR-0010: Start with a modular monolith](0010-modular-monolith.md)
- [ADR-0011: Deploy with docker-compose and 12-factor configuration](0011-deployment-12factor.md)
- [ADR-0019: Use Brevo for email and Twilio for SMS notifications](0019-notification-delivery-providers.md)
- Issue #56 (this decision) and #57 (implementation)
