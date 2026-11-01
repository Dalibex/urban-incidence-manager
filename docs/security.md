# Security

How the API authenticates requests and checks permissions ([ADR-0004](adr/0004-authentication-strategy.md)).
Every feature that adds or changes an endpoint updates the [permission matrix](#permission-matrix)
in the same pull request.

## Access tokens

Every request, except the public operations below, carries a JWT access token:

```http
Authorization: Bearer <access token>
```

| Item | Value |
| --- | --- |
| Signature | HS256 with `JWT_SECRET` (at least 32 bytes). Any other algorithm, including `none`, is rejected. |
| Type (`typ` header) | `at+jwt` ([RFC 9068](https://www.rfc-editor.org/rfc/rfc9068)). A JWT signed for another purpose is not accepted as an access token. |
| Issuer (`iss`) | `urbanpulse` |
| Claims | `sub` user id (UUID), `role`, `sid` id of the refresh token issued with it, `iat`, `exp`. `exp` and `sid` are required. |
| Lifetime | `JWT_ACCESS_TOKEN_TTL`, 15 minutes by default (ISO-8601 duration, `PT15M`) |
| Clock skew | 60 seconds of tolerance on `exp` |

The token is signed, not encrypted: anyone who holds it can read it, so it carries no personal data
(no name, no email). The role in the token is trusted until the token expires: a role change or a
disabled account takes effect, at the latest, when the current access token expires, because the
refresh token is revoked and no new access token is issued.

### Configuration

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `JWT_SECRET` | Yes | — | Signing secret. The application does not start without it or with fewer than 32 bytes. |
| `JWT_ACCESS_TOKEN_TTL` | No | `PT15M` | Lifetime of the access tokens. |
| `REFRESH_TOKEN_TTL` | No | `P14D` | Lifetime of the refresh tokens. After it, the user signs in again. |

Locally the variables come from `backend/.env` ([development guide](development.md)); in a container
or in production, from the environment. Never commit a real secret, never log it, and use a
different one in every environment: whoever knows it can sign a token for any user and role.

## Sessions and passwords

- **Passwords** are hashed with bcrypt (cost 10) behind a prefix (`{bcrypt}$2a$10$...`), so a
  stronger algorithm can become the default later without resetting anyone's password. They are 8
  characters to 72 bytes of UTF-8: bcrypt ignores anything after 72 bytes.
- **A session is a refresh token.** The token is 256 random bits; the database only keeps its SHA-256
  hash, so a copy of the database does not give working tokens. The access token carries the id of
  its refresh token (`sid`), which is what sign-out revokes.
- **Rotation:** each refresh revokes the token used and issues a new pair. The revocation is a
  conditional `UPDATE` (`... WHERE revoked_at IS NULL`), so two requests with the same token at once
  cannot both succeed.
- **Sign-out** revokes only that session; other devices stay signed in. A disabled account loses
  every session at its next refresh, and `SessionService.revokeAllSessions` revokes every refresh
  token at once. In both cases the access tokens already issued keep working until they expire
  (about 15 minutes): that is the price of not checking the database on every request (ADR-0004).
- **Sign-in with an unknown email** still compares the password against a hash, so it takes as long
  as a wrong password and the response time does not reveal which emails have an account.

### Rate limits

| Operation | Limit | Key |
| --- | --- | --- |
| `POST /sessions` | 5 failed attempts per 15 minutes | Email and client address |
| `POST /registrations` | 10 attempts per hour | Client address |

Past the limit the API answers `429 TOO_MANY_ATTEMPTS` with `Retry-After` (seconds, rounded up),
even for the right password. Each attempt is reserved before the password is checked, in one atomic
step, so a burst of parallel requests cannot go past the limit; a right password gives the attempts
back, so only failures add up. The limits are properties (`urbanpulse.security.sign-in-attempts.max` / `.window`,
`urbanpulse.security.sign-up-attempts.max` / `.window`).

Known limits:

- The counters live in the backend process. That is correct with one replica (ADR-0011); with more,
  each replica would allow the full limit and they would have to move to a shared store such as
  Redis (ADR-0012). A restart resets them.
- The key is the email and the address, so one address can still try 5 passwords on each of many
  emails (password spraying), and the counters of one window grow with every email tried. A global
  limit per address is the next step if that becomes a problem.
- Revoked and expired refresh tokens stay in `refresh_token`; a periodic clean-up is pending.
- The client address is the TCP peer, not `X-Forwarded-For`, because no proxy sits in front of the
  backend yet. When one is added (ADR-0017), configure Spring to trust only that proxy
  (`server.forward-headers-strategy`); trusting the header from anyone would let a client skip the
  limit.

## Audit trail

Administrative actions and relevant decisions are recorded in `audit_log` (RF18): who (`actor_id`),
when (`instant`, UTC), what (`action`), on which record (`target_type`, `target_id`) and `details`,
a JSON object with the values the change replaced and the ones it set:
`{"before": {"role": "TECHNICIAN"}, "after": {"role": "MUNICIPAL_OPERATOR"}}`. It is domain data, not
an operational log: the application logs on stdout never replace it.

| Action | Recorded by |
| --- | --- |
| `CREATE_USER`, `UPDATE_USER`, `CHANGE_ROLE`, `DISABLE_USER`, `ENABLE_USER` | User management (#40) |
| `CLASSIFY_INCIDENT` | Incident classification (#47) |

Rules for code that records an entry:

- **Same transaction as the change.** Call `AuditService.record` from inside the transaction of the
  use case. It refuses to run without one (`Propagation.MANDATORY`), so a change and its entry are
  committed or rolled back together.
- **No secrets or unneeded personal data in `details`.** Record identifiers and domain values (a
  role, a priority, a category id), never a password, hash, token, email or name. `record` only
  accepts scalar values, so a whole entity cannot be recorded by mistake. A field that had no value
  is recorded as `null`, not left out.
- **Append-only.** Nothing updates or deletes an entry: the entity is immutable (no `UPDATE`), the
  repository has no `delete`, and a trigger (migration V8) rejects `UPDATE`, `DELETE` and
  `TRUNCATE` on the table. For the same reason an `AuditAction` constant is never renamed or removed.

Known limits:

- The trigger guards against mistakes, not against someone with the database owner or superuser
  account, who can disable it. Separate database roles for migrations and for the application would
  close that gap.
- A user who appears in an entry can no longer be deleted: the actor's foreign key and the trigger
  stop it. Accounts are disabled instead (#40); erasing personal data on request would need its own
  procedure.

## Errors

The filters answer before any controller runs, with the contract's Problem Details body
(`application/problem+json`):

| Status | When | `detail` |
| --- | --- | --- |
| `401` + `WWW-Authenticate: Bearer` | No token, malformed, wrong signature or type, expired, wrong issuer, unknown role or missing claim | The same text for every cause, so a client cannot probe which check failed |
| `403` | Valid token, but the role does not allow the action | — |

When the role allows the action but the resource is not the caller's (an incident they did not
report, or that is not assigned to them), the service answers `404`, as if it did not exist, so the
API does not reveal which ids exist. The contract says so in each operation.

Sign-up, sign-in and refresh run in their own filter chain without token validation: a client that
sends its expired access token along with the refresh request still gets the refresh.

## CSRF

CSRF protection is disabled in `SecurityConfig` on purpose. A cross-site request forgery works
because the browser attaches a site's cookies to any request sent to it, even from another site's
page. This API never authenticates with something the browser sends by itself:

- The access token travels in the `Authorization` header, which only our own client code adds. A page
  on another origin cannot read the token nor add the header to a request to the API.
- There is no session and no cookie: the filter chains are stateless and the API never sends
  `Set-Cookie`. `JwtAuthenticationIT` checks this, so a change that introduces cookies breaks a test.

Static analysis flags the disabled protection anyway (CodeQL `java/spring-disabled-csrf-protection`):
it cannot see that no cookie carries a credential. The alert is dismissed as a false positive with a
link to this section.

**When this stops being true:** if any credential ever travels in a cookie (for example, the refresh
token in an `HttpOnly` cookie for the web client), the endpoints that accept it are open to CSRF.
That change needs an update of [ADR-0004](adr/0004-authentication-strategy.md) and CSRF protection
(or `SameSite=Strict` plus a check of the `Origin` header) for those endpoints, in the same pull
request.

## Permission matrix

Paths are relative to `/api/v1`. **Public** means no token. A role in the cell can call the
operation; the note says when the answer also depends on the resource. A role not listed gets `403`.

| Operation | Public | CITIZEN | MUNICIPAL_OPERATOR | TECHNICIAN | ADMINISTRATOR | ANALYST |
| --- | --- | --- | --- | --- | --- | --- |
| `POST /registrations` | ✓ | | | | | |
| `POST /sessions` | ✓ | | | | | |
| `POST /sessions/refresh` | ✓ | | | | | |
| `DELETE /sessions/current` | | ✓ | ✓ | ✓ | ✓ | ✓ |
| `GET /users/me` | | ✓ | ✓ | ✓ | ✓ | ✓ |
| `GET /categories` | | ✓ | ✓ | ✓ | ✓ | ✓ |
| `GET /incidents` | | own | ✓ | assigned | ✓ | |
| `POST /incidents` | | ✓ | | | | |
| `GET /incidents/{id}` | | own | ✓ | assigned | ✓ | |
| `GET /incidents/{id}/status-changes` | | own, citizen view | ✓ | assigned | ✓ | |

Outside `/api/v1` only `/actuator/health/**` and the API documentation (`/openapi.yml`,
`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`) are public.

"own" and "assigned" are checked by the service with the data of the resource, not by the role:
being authenticated or knowing an id never gives access to an incident (see `AGENTS.md`, section 6).
Listings only return the caller's incidents; reading one that is not theirs answers `404`.

## Protecting a new endpoint

1. Add the operation to the matrix above and to `docs/api/openapi.yml` (`401`, and `403` when the role
   matters).
2. Everything under `/api/v1` already needs a token. If the operation must be public, add it to
   `SecurityConfig` and mark it with `security: []` in the contract.
3. Check the role with `@PreAuthorize("hasRole('MUNICIPAL_OPERATOR')")` (or `hasAnyRole(...)`) on the
   controller method.
4. Read the caller with `@AuthenticationPrincipal AuthenticatedUser user` and pass `user.id()` and
   `user.role()` to the service, which checks the relationship with the resource.
5. In the integration tests, `@Import(TestTokens.class)` and send `tokens.bearer(Role.X)` (or
   `tokens.bearer(userId, Role.X)` for an existing user): one test per allowed role and at least one
   for a role that gets `403`, plus one without a token (`401`).

A global `@RestControllerAdvice` must not catch `AccessDeniedException` or `AuthenticationException`
(for example through a handler for `Exception`): rethrow them. The `403` of `@PreAuthorize` is
written by the security filters after the exception leaves the controller; if an advice catches it
first, the client gets `500`.
