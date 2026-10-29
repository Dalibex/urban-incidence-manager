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

Locally the variables come from `backend/.env` ([development guide](development.md)); in a container
or in production, from the environment. Never commit a real secret, never log it, and use a
different one in every environment: whoever knows it can sign a token for any user and role.

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
