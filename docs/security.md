# 🔐 Security Guide

How the API authenticates requests and checks permissions
([ADR-0004](adr/0004-authentication-strategy.md)), and the security limits the team knows about.

> [!IMPORTANT]
> Every feature that adds or changes an endpoint updates the
> [permission matrix](#-permission-matrix) in the same pull request, and every security alert gets
> its row in [Security Findings](#-security-findings).

## 📑 Contents

- **[Access Tokens](#-access-tokens)**: the JWT, its claims, its lifetime and its configuration.
- **[Sessions and Passwords](#-sessions-and-passwords)**: refresh tokens, rotation and reuse, sign-out, password changes and rate limits.
- **[Second Factor](#-second-factor)**: the TOTP code that staff sign-ins need, its enrolment and its reset.
- **[First Administrator](#-first-administrator)**: the rules of the one-off process that creates it.
- **[Audit Trail](#-audit-trail)**: what is recorded, who reads it and the rules for writing it.
- **[Errors](#-errors)**: the `401`, `403` and `404` answers of security.
- **[CSRF](#-csrf)**: why the protection is off, and when it must not be.
- **[Browser Storage](#-browser-storage)**: what the web app keeps in the browser.
- **[Data Sent Outside](#-data-sent-outside)**: what the external services receive, and how to turn each one off.
- **[Permission Matrix](#-permission-matrix)**: who may call each operation.
- **[Protecting a New Endpoint](#-protecting-a-new-endpoint)**: the steps and the tests.
- **[Security Findings](#-security-findings)**: every alert, and what was done about it.

## 🎫 Access Tokens

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
disabled account takes effect, at the latest, when the current access token expires. Disabling an
account revokes its refresh tokens, so no new access token is issued. A role change revokes nothing:
the next refresh issues an access token with the new role.

### Configuration

`JWT_SECRET`, `JWT_ACCESS_TOKEN_TTL`, `REFRESH_TOKEN_TTL` and `REFRESH_TOKEN_RETENTION`, with their
defaults, are in *Configuration* in the [deployment guide](deployment.md). The
backend does not start without `JWT_SECRET` or with one under 32 bytes. Never commit a real secret,
never log it, and use a different one in every environment: whoever knows it can sign a token for
any user and role.

## 🔒 Sessions and Passwords

- **Passwords** are hashed with bcrypt (cost 10) behind a prefix (`{bcrypt}$2a$10$...`), so a
  stronger algorithm can become the default later without resetting anyone's password. They are 8
  characters to 72 bytes of UTF-8: bcrypt ignores anything after 72 bytes. The minimum counts
  characters (code points), as the contract does, so a password of 4 emoji is too short even though
  it is 8 UTF-16 units (#285).
- **A session is a refresh token.** The token is 256 random bits; the database only keeps its SHA-256
  hash, so a copy of the database does not give working tokens. The access token carries the id of
  its refresh token (`sid`), which is what sign-out revokes.
- **Rotation:** each refresh revokes the token used and issues a new pair. The refresh locks the row
  of the user and then the one of the token (`SELECT ... FOR UPDATE`), so the refreshes of one user
  run one after another: two requests with the same token cannot both succeed, and revoking every
  session never misses the one that a refresh with another token is opening at that moment.
- **Reuse of a revoked refresh token revokes every session of its user.**
  - **Why:** a token that is presented again after a refresh or a sign-out revoked it, and before it
    expires, means that two parties hold it: if a thief refreshed first, the thief has the live
    session and the user holds the spent token. The server cannot tell which one is the user, so it
    revokes every active session of that user and logs a warning with the session and user ids
    (never the token).
  - **The answer** is the same `401 INVALID_REFRESH_TOKEN` as for an unknown or expired token, so it
    does not tell the client what happened. An expired token is only rejected, even if it had been
    revoked.
  - **Only a refresh or a sign-out** leaves a revoked token that counts as reused. Revoking every
    session at once (a password change, a disabled account, or a reuse itself) also makes every
    token of the user that has not expired expire at that instant, including those a refresh had
    already revoked (`RefreshTokenRepository.endAll`). Presenting one of them later is only
    rejected, so it never closes the sessions opened afterwards, such as the one opened with the
    new password (#275).
  - **The price** is a false alarm when the answer of a refresh is lost (the network drops after the
    server rotated the token, or the app is closed before it saves the new pair): the client still
    holds the spent token, and the user is signed out of every device at its next refresh.
    `api-client` makes that case possible on purpose: when a refresh fails without an answer, or
    with any error other than `401`, it keeps the tokens, because the session may still be valid,
    and sends the same refresh token at the next `401`. Otherwise it never sends a token twice: it
    shares one refresh among concurrent requests and discards the tokens on sign-out.
  - **A refresh still in flight at sign-out** does not bring the tokens back: if it answers while
    sign-out waits for it, its fresh token revokes the session; if it answers later, its tokens are
    dropped. In neither case is a rejection reported as an expired session, and a refresh is not
    sent if sign-out cleared the store while it was being read. A refresh that sign-out could not
    use to revoke the session (it answers later, or the `DELETE` itself fails) leaves a session
    alive on the server until its refresh token expires (#237).
  - **A request is only repeated with the session it was sent with.** Once that session has ended,
    because the user signed out or because its refresh was rejected, a request of that session that
    gets `401` is neither refreshed nor repeated, so it never runs as the user who signs in next,
    nor with the token the sign-out refreshed. A request sent while the sign-out waits for the
    server belongs to the session being signed out; only the sign-out's own `DELETE` is still
    repeated with a fresh token, to revoke the session. The photo upload follows the same rule, and
    so do the automatic retries of an upload on the mobile app (#259).
- **Clean-up:** a job inside the backend deletes, every day at 03:00 UTC, the refresh tokens that
  expired longer ago than `REFRESH_TOKEN_RETENTION` (`RefreshTokenCleanup`). It deletes by expiry,
  not by revocation: a revoked token is kept while it would still work, because deleting it would
  turn its reuse into an unknown token and the reuse would go unnoticed. The job assumes a single
  backend replica (ADR-0011); with more, each one would run it, which only deletes the same rows.
- **Sign-out** revokes only that session; other devices stay signed in. A disabled account loses
  every session at its next refresh, and `SessionService.revokeAllSessions` revokes every refresh
  token at once and makes it expire. In both cases the access tokens already issued keep working
  until they expire (about 15 minutes): that is the price of not checking the database on every
  request (ADR-0004).
- **Sign-in with an unknown email** still compares the password against a hash, so it takes as long
  as a wrong password and the response time does not reveal which emails have an account.
- **Forced change at the first sign-in (#165).** An account with `password_change_required` gets
  `403 PASSWORD_CHANGE_REQUIRED` and no tokens from sign-in, so the password an administrator typed
  for it never opens a session. Sign-in only says so when the password is right, after the check
  of a disabled account, so a wrong password does not reveal it. Every account an
  administrator creates has it (`POST /users`, #191), and so does the dev user `dev.newstaff`.
- **Password change** (`POST /password-changes`) is public: an account that must change its
  password has no tokens yet, so the current password is the credential.
  - **Checked like sign-in:** the same `401` for an unknown email, in a comparable time, and the
    same rate limit.
  - **All or nothing:** the new hash, the end of the obligation, the revocation of every refresh
    token of the user and the `CHANGE_PASSWORD` entry are one transaction, so a failure in any of
    them leaves the old password.
  - **Two changes at once:** bcrypt runs before the transaction, which locks the user row and checks
    that the hash is still the one checked, so of two changes with the same current password the
    second gets `401`.
  - **What it does not stop:** the access tokens already issued keep working until they expire, as
    when an account is disabled. A sign-in that checks the old password at the same moment a change
    commits can still open a session with it, because sign-in does not lock the user row: the
    window is a few milliseconds and needs the old password.

### Rate limits

| Operation | Limit | Key |
| --- | --- | --- |
| `POST /sessions` and `POST /password-changes` | 5 failed attempts per 15 minutes, counted together | Email and client address |
| `POST /registrations` | 10 attempts per hour | Client address |
| `POST /mfa-verifications` | 10 wrong codes per 15 minutes, separate from the password limit; and 5 wrong codes spend a challenge | Account only (not the client address) |
| `GET /places` | 10 searches a minute (`urbanpulse.places.searches-per-user`), even those answered from the cache; and one call a second to Nominatim for every user together, shared with the address worker: a search waits up to a second for its turn, and one refused because the turn is further away does not count against the user | User, and the whole backend |

Past the limit the API answers `429 TOO_MANY_ATTEMPTS` with `Retry-After` (seconds, rounded up),
even for the right password. Each attempt is reserved before the password is checked, in one atomic
step, so a burst of parallel requests cannot go past the limit; a right password gives the attempts
back, so only failures add up. The limits are properties (`urbanpulse.security.sign-in-attempts.max` / `.window`,
`urbanpulse.security.sign-up-attempts.max` / `.window`).

In the deployment, nginx also limits the public identity operations (sign-up, sign-in, refresh
and password change) to 30 requests a minute per client address, with a burst of 20
(`limit_req` in `frontend/nginx.conf`). Past it, nginx answers `429` without a body of the API and
the request never reaches the backend. It only stops floods: these operations need no token, and
each sign-in checks a password hash.

Known limits:

- The counters live in the backend process. That is correct with one replica (ADR-0011); with more,
  each replica would allow the full limit and they would have to move to a shared store such as
  Redis (ADR-0022). A restart resets them.
- The key is the email and the address, so one address can still try 5 passwords on each of many
  emails (password spraying), and the counters of one window grow with every email tried. A global
  limit per address is the next step if that becomes a problem.
- **The client address** is the TCP peer of the backend (`getRemoteAddr()`).
  - **In development** that is the client itself, and the backend ignores any `X-Forwarded-*`
    header it sends.
  - **In the deployment Compose** the peer is nginx (ADR-0017), so
    `SERVER_FORWARD_HEADERS_STRATEGY=native` makes Tomcat take the client address from
    `X-Forwarded-For`, and the scheme, host and port of the `Location` of a `201` from
    `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Port` (#200). Tomcat only reads them
    from nginx: `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` is the fixed address of `web` on the
    `internal` network (`10.203.217.10`), and the `lint` job fails if the two differ. A request
    from any other peer keeps that peer's own address, whatever headers it sends.
  - **Why a client cannot forge them:** nginx writes all four itself at the `server` level of
    `frontend/nginx.conf`, so what a client sends never reaches the backend, and it removes the
    client's `Forwarded` and `X-Forwarded-Prefix`, which the backend would trust if it ever switched
    to `server.forward-headers-strategy=framework`. The `build` job fails if a `location` sets a
    `proxy_set_header` of its own, or if a forged header reaches the backend.
  - **Who else reaches the backend:** nobody but the services of `internal`. It publishes no port,
    and `internal` gives the host no address (`gateway_mode_ipv4: isolated`), so not even a process
    on the server can call it without nginx. The trust in nginx's address alone stays as a second
    barrier: if another container ever reached the backend, its forged headers would be ignored and
    it would be limited by its own address ([deployment guide](deployment.md#networks)).
- On Docker Desktop (Windows and macOS), Docker hides the address of the devices that connect from
  outside its virtual machine, so nginx sees one address for all of them and they share the
  limits. On a Linux machine each device that connects over IPv4 arrives with its own address.
- Devices that connect over IPv6 share the limits on any machine. The Compose networks are IPv4
  only, so Docker passes those connections on through its own proxy and nginx sees the gateway of
  the network (`172.x.0.1`) for all of them. Today the clients reach the deployment by an IPv4
  address, so this only matters once its name has an IPv6 (`AAAA`) record.
- Clients behind one public address share the limits: a university wifi or some mobile operators
  share the 10 sign-ups per hour, and someone on the same network can still block a person's
  sign-in. For a demo, create the users beforehand with the `demo` dataset
  ([deployment guide](deployment.md#-shared-limits)).
- The host of the `Location` comes from the `Host` the client sent, because `server_name _`
  accepts any. A client can only change the links of its own answers, so it harms nobody today.
  Before the backend sends absolute links to other people (notifications, emails), list the
  public domain in `server_name`.

## 🔑 Second Factor

Staff accounts confirm each sign-in with a code of an authenticator app (TOTP, RFC 6238), as
[ADR-0021](adr/0021-multi-factor-authentication.md) decided (#57). The rule is enforced by the API on
the role of the account, whatever the client, so a staff account cannot sign in from the mobile
app, which has no code step.

- **Who:** the roles in `MFA_REQUIRED_ROLES`, every staff role by default. Citizens never get it:
  the backend refuses to start with `CITIZEN` in the list, since the mobile app has no code step.
  An empty value turns the second factor off, which only makes sense on a developer's machine; the
  backend logs a warning when it starts that way.
- **Two steps:** a right password returns `202` with a challenge and no tokens. The challenge is an
  opaque, single-use token of 256 random bits that expires after 5 minutes. The database keeps its
  SHA-256 hash only, like a refresh token, and the web app keeps the token in memory. The token pair
  is issued by `POST /mfa-verifications` once a valid code is sent, in the same transaction that
  spends the challenge.
- **Refresh:** each refresh token records whether its session was opened with a code, and a
  rotation keeps that mark. A refresh for an account whose role needs the second factor with a
  token opened without it (a session from before #57, or from before a role change or a change of
  `MFA_REQUIRED_ROLES`) refuses that token with `401` and ends it, and the user signs in again with
  a code. Other sessions of the account, such as one just opened with a code, keep working.
- **Codes:** 6 digits, SHA-1, 30-second steps; a code is valid for its step and one step on each
  side. The last step used is stored per account, so a code that was already used, or an older one,
  is refused, even with another challenge. Two verifications of one challenge at once lock the
  account and the challenge rows: one gets the tokens, the other `401 INVALID_MFA_CHALLENGE`.
- **Enrolment:** an account without a second factor gets `enrollmentRequired: true` and calls
  `POST /mfa-enrollments`, which returns the secret as an `otpauth://` URI for the QR code and as
  Base32 text. The secret belongs to that challenge until a first valid code confirms it: only then
  is it copied to the account and the `ENROLL_MFA` entry recorded, and every other open challenge of
  the account is spent. No operation returns the secret after that.
- **Secret at rest:** encrypted with AES-256-GCM (random 12-byte IV per secret) with the key of
  `MFA_ENCRYPTION_KEY`, 32 random bytes in Base64. The backend does not start without it. Losing or
  changing the key makes every stored secret unreadable: the code step of every enrolled account
  answers `500`, administrators included, so nobody can reset from the web and each account needs the
  [emergency procedure](deployment.md#-reset-the-second-factor-of-the-last-administrator) (for
  another user, the same SQL with their email and without the role condition).
- **Reset:** `POST /users/{id}/mfa-resets`, administrators only, with a reason. It clears the secret,
  spends the open challenges, revokes every refresh token of the user and records `RESET_MFA` with the
  reason, in one transaction. The user enrols again on the next sign-in. A last administrator who
  loses their phone follows the emergency procedure of the
  [deployment guide](deployment.md#-reset-the-second-factor-of-the-last-administrator).
- **Logs:** a wrong code is logged with the user id only. The secret, the codes and the challenge
  tokens are never written to the logs.

Known limits, also in *Accepted Risks* of the [architecture](architecture.md):

- **Trust on first use.** While an account has no second factor (after the deployment of #57, after
  a reset, and for a new account), whoever knows its password can enrol their own phone first. The
  real user's sign-in then asks for a code their app cannot give, and an administrator resets it.
- **Lock-out by whoever has the password.** The limit of wrong codes is per account and a right
  password does not give the attempts back, so someone with the password can lock the real user
  out of the code step for up to 15 minutes. The counter lives in the backend process, like the
  password limit.
- **A password change does not ask for a code.** Whoever knows the password of a staff account can
  change it without the phone, and the real user is then locked out until an administrator gives
  them a new password; their data is still protected, because a sign-in with the new password asks
  for the code too. A password change spends the open challenges, so a sign-in started with the old
  password cannot finish. A reset does not require a new password either.

## 👑 First Administrator

Sign-up only creates citizens and staff accounts are created by an administrator (#40), so a clean
deployment gets its first administrator from a one-off admin process (#126, 12-factor XII), run
with the same image and configuration as the backend. How to run it is in
[the deployment guide](deployment.md#-create-the-first-administrator).

- The name, email and password come from `BOOTSTRAP_ADMIN_NAME`, `BOOTSTRAP_ADMIN_EMAIL` and
  `BOOTSTRAP_ADMIN_PASSWORD`, never from the code or the repository, and follow the rules of
  sign-up. The process never prints the password.
- It is idempotent: when an enabled administrator exists, it changes nothing and does not check the
  values. When the email belongs to another account, it fails without promoting it, since that
  would give the role to whoever signed up with the email first.
- It only runs with the `bootstrap-admin` profile; a normal start of the backend never runs it.
- Its audit entry is `CREATE_USER` with the new administrator as its own actor, because
  `actor_id` is required and no administrator exists yet, and `"origin": "BOOTSTRAP"` in `after`.
  No other action records an account as the actor of its own creation.
- The administrator is not obliged to change the password of the environment, but can with
  `POST /password-changes`. Remove it from the environment once the account exists.

## 📜 Audit Trail

Administrative actions and relevant decisions are recorded in `audit_log` (RF18): who (`actor_id`),
when (`instant`, UTC), what (`action`), on which record (`target_type`, `target_id`) and `details`,
a JSON object with the values the change replaced and the ones it set:
`{"before": {"role": "TECHNICIAN"}, "after": {"role": "MUNICIPAL_OPERATOR"}}`. It is domain data, not
an operational log: the application logs on stdout never replace it.

| Action | Recorded by |
| --- | --- |
| `CREATE_USER`, `UPDATE_USER`, `CHANGE_ROLE`, `DISABLE_USER`, `ENABLE_USER` | User management (#40) |
| `CHANGE_PASSWORD` | Password change (#165), with the account as its own actor: only its owner knows the password. `details` holds `passwordChangeRequired`, never a password or hash |
| `CLASSIFY_INCIDENT` | Incident classification (#47) |
| `ENROLL_MFA` | The first valid code of an enrolment (#57), with the account as its own actor. `details` never holds the secret |
| `RESET_MFA` | An administrator resets a user's second factor (#57); `after` holds the `reason` |
| `CREATE_USER` (`"origin": "BOOTSTRAP"`) | The one-off process that creates the first administrator (#126) |

Staff read one part of the trail: the classification changes of an incident
(`GET /incidents/{id}/classification-changes`, #47) are its `CLASSIFY_INCIDENT` entries, read with
no second copy, for operators, administrators and the technician assigned to it. They show only the
category, the priority and its justification, and the actor's id and name. The rest of the trail is
for administrators (`listAuditEntries`, #41).

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

Administrators read the whole trail through `GET /audit-entries` and the *Audit log* screen (#41).
The listing shows each actor's and record's current name, read with the page, so the entries
themselves never need to store a name.

Known limits:

- The trigger guards against mistakes, not against someone with the database owner or superuser
  account, who can disable it. Separate database roles for migrations and for the application would
  close that gap.
- A user who appears in an entry can no longer be deleted: the actor's foreign key and the trigger
  stop it. Accounts are disabled instead (#40); erasing personal data on request would need its own
  procedure.

## 🚫 Errors

The token and role checks answer before Spring MVC reads anything of the request (body,
parameters, path variables, the multipart upload, `Content-Type` or `Accept`). The firewall refuses
a header value or a parameter name when it is read, which may be inside Spring MVC;
`ApiExceptionHandler` passes that refusal on to the same handler. Every one of these answers has
the contract's Problem Details body (`application/problem+json`):

| Status | When | `detail` |
| --- | --- | --- |
| `400` + `MALFORMED_REQUEST` | The firewall refuses the request: a path with `//`, `;` or `%2F`, a parameter name with a control character, or a header value it refuses when it is read | The same text for every cause, and no `instance`: the refused path is not echoed back |
| `401` + `WWW-Authenticate: Bearer` | No token, malformed, wrong signature or type, expired, wrong issuer, unknown role or missing claim | The same text for every cause, so a client cannot probe which check failed |
| `403` | Valid token, but the role does not allow the action, or the operation has no rule (deny by default, see the [permission matrix](#-permission-matrix)) | — |

So a role that may not call an operation gets `403` before any `400`, `413`, `415` or `422` its
request would get, and learns nothing of what a valid request looks like (#286). The `401` comes
before the `403`.

When the role allows the action but the resource is not the caller's (an incident they did not
report, or that is not assigned to them), the service answers `404`, as if it did not exist, so the
API does not reveal which ids exist. The contract says so in each operation.

Sign-up, sign-in, refresh and the password change run in their own filter chain without token validation: a client that
sends its expired access token along with the refresh request still gets the refresh.

## 🍪 CSRF

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

> [!IMPORTANT]
> **When this stops being true:** if any credential ever travels in a cookie (for example, the
> refresh token in an `HttpOnly` cookie for the web client), the endpoints that accept it are open to
> CSRF. That change needs an update of [ADR-0004](adr/0004-authentication-strategy.md) and CSRF
> protection (or `SameSite=Strict` plus a check of the `Origin` header) for those endpoints, in the
> same pull request.

## 💾 Browser Storage

The web app keeps the tokens in memory only (`SessionContext`), never in `localStorage` or
`sessionStorage`, where any script of the page could read them. The challenge of a sign-in with a
second factor and the secret of an enrolment are kept in memory too, and only while that sign-in
lasts. The only things it writes there are
which parts of the map screen each user folded or opened, under a key with the user's id
(`urbanpulse.map-preferences.<user id>`, #177), and the filters each user last used on the inbox,
the work queue and the map (`urbanpulse.list-filters.<list>.<user id>`, #263). The values hold no
personal data and the API never reads them: the text search of the map (#266), which is free text,
is never kept there. They stay after sign-out because they are only a layout
and a convenience, so on a shared browser the storage keeps the ids of the users who opened those
screens there. If the browser refuses its storage, the screen works with the defaults.

## 📤 Data Sent Outside

Two public services receive the position of every new report, after the report is saved and never
during it. The backend calls each one once per incident, again only after a failed call, over
HTTPS, and in the deployment only through `egress-proxy`, which sees the host name and not the
request.

| Service | What it receives | Why | How to turn it off |
| --- | --- | --- | --- |
| Open-Meteo (`api.open-meteo.com`, [ADR-0018](adr/0018-weather-and-traffic-providers.md), #307) | The latitude and longitude of the report | The weather at the position | `WEATHER_ENRICHMENT_ENABLED=false` |
| Nominatim of OpenStreetMap (`nominatim.openstreetmap.org`, [ADR-0002](adr/0002-open-street-map.md), #375, #179) | The latitude and longitude of the report; the text of an address search of the report form, never who searched it; and a `User-Agent` that names the application, as its usage policy asks | The normalized address at the position; the places of Málaga that match the search | `ADDRESS_ENRICHMENT_ENABLED=false` stops both: the address of the reports, and the search, which then answers `503` without calling Nominatim. On, the search is called only by a citizen who submits it |

Neither receives anything else: no title, description, photo, account, token or IP address of the
citizen, and no identifier of the incident. A position alone can still point to a home, so the
backend never writes it to its logs: the errors of both calls name the kind of failure, never the
URL that holds the coordinates. With a variable set to `false` the backend calls nothing, and new
incidents keep their weather or address pending ([deployment guide](deployment.md)).

## 🧮 Permission Matrix

Paths are relative to `/api/v1`. **Public** means no token. A role in the cell can call the
operation; the note says when the answer also depends on the resource. A role not listed gets `403`.

The security filters enforce the role columns, one rule per operation in `ApiAuthorizationRules`
(`backend/src/main/java/es/uma/urbanpulse/shared/security`), and the public operations in
`SecurityConfig`. Under `/api/v1` a request that matches no rule is refused to every role (deny by
default): an endpoint added without its rule answers `403`. So does a method an operation does not
have, such as `DELETE /incidents/{id}`, `HEAD` or `OPTIONS`, instead of `405`, and a path no rule
matches, such as `/api/v1/nothing`, instead of `404`. A path that a rule matches still reaches
Spring MVC: `GET /incidents/` matches `/incidents/*` with an empty segment, so a role it allows gets
`404`.

| Operation | Public | CITIZEN | MUNICIPAL_OPERATOR | TECHNICIAN | ADMINISTRATOR | ANALYST |
| --- | --- | --- | --- | --- | --- | --- |
| `POST /registrations` | ✓ | | | | | |
| `POST /sessions` | ✓ | | | | | |
| `POST /sessions/refresh` | ✓ | | | | | |
| `POST /password-changes` | ✓ | | | | | |
| `POST /mfa-enrollments` | ✓, the challenge is the credential | | | | | |
| `POST /mfa-verifications` | ✓, the challenge and the code are the credentials | | | | | |
| `DELETE /sessions/current` | | ✓ | ✓ | ✓ | ✓ | ✓ |
| `GET /users/me` | | ✓ | ✓ | ✓ | ✓ | ✓ |
| `GET /users` | | | | | ✓ | |
| `POST /users` | | | | | ✓, staff roles only | |
| `GET /users/{id}` | | | | | ✓ | |
| `PATCH /users/{id}` | | | | | ✓, staff roles only, not their own role or account | |
| `POST /users/{id}/mfa-resets` | | | | | ✓ | |
| `GET /categories` | | ✓ | ✓ | ✓ | ✓ | ✓ |
| `GET /departments` | | | ✓ | ✓ | ✓ | |
| `GET /technicians` | | | ✓ | | ✓ | |
| `GET /incidents` | | own | ✓ | assigned | ✓ | |
| `POST /incidents` | | ✓ | | | | |
| `GET /incidents/{id}` | | own | ✓ | assigned | ✓ | |
| `PATCH /incidents/{id}` | | | ✓ | | ✓ | |
| `GET /incidents/{id}/classification-changes` | | | ✓ | assigned | ✓ | |
| `GET /incidents/{id}/status-changes` | | own, citizen view | ✓ | assigned | ✓ | |
| `POST /incidents/{id}/validation` | | | ✓ | | ✓ | |
| `POST /incidents/{id}/rejection` | | | ✓ | | ✓ | |
| `POST /incidents/{id}/information-requests` | | | ✓ | | ✓ | |
| `POST /incidents/{id}/assignments` | | | ✓ | | ✓ | |
| `POST /incidents/{id}/acceptance` | | | | assigned | | |
| `POST /incidents/{id}/resolution` | | | | assigned | | |
| `POST /incidents/{id}/closure` | | | ✓ | | ✓ | |
| `POST /incidents/{id}/reopening` | | | ✓ | | ✓ | |
| `GET /incidents/{id}/attachments` | | own, only their photos | ✓ | assigned | ✓ | |
| `POST /incidents/{id}/attachments` | | own, only while `REPORTED` | ✓ | assigned | ✓ | |
| `GET /attachments/{id}/content` | | own, only their photos | ✓ | assigned | ✓ | |
| `GET /incidents/{id}/comments` | | | ✓ | assigned | ✓ | |
| `POST /incidents/{id}/comments` | | | ✓ | assigned | ✓ | |
| `GET /incidents/{id}/urban-context` | | | ✓ | assigned | ✓ | |
| `GET /incident-locations` | | | ✓ | assigned | ✓ | |
| `GET /incident-statistics` | | | ✓ | assigned | ✓ | |
| `GET /audit-entries` | | | | | ✓ | |
| `GET /incident-reports` | | | | | ✓ | ✓ |
| `GET /places` | | ✓ | | | | |

User management is for administrators only. They cannot change their own role or disable their
own account, nor give a staff role to a citizen account or the reverse (`422`). Nor can they
disable a technician, or change the technician's role, while an incident `IN_PROGRESS` is assigned
to them, because only that technician can resolve it ([ADR-0020](adr/0020-incident-lifecycle-transitions.md)):
the `422` lists the first five titles, which an administrator can read anyway. The acceptance of an
incident and the change of the account lock the technician's row, so they never pass each other,
and a disabled technician whose access token has not expired yet can no longer accept nor resolve
(`409`, #274). Two administrators who demote or disable each other at the same time can
still leave none enabled: the `bootstrap-admin` profile then creates one again, because it only
runs when no enabled administrator exists. A role change or a disabled account reaches the access
tokens already issued at their next refresh, at most one access token lifetime later (ADR-0004).

Outside `/api/v1` only `/actuator/health/**`, `/actuator/prometheus` and the API documentation
(`/openapi.yml`, `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`) are public.
`/actuator/prometheus` gives Prometheus the backend's metrics for the load tests
(`backend/load-tests/practice/README.md`): counts and timings per endpoint, the JVM and the
connection pool, without request data or user identifiers. It is only reachable where the
backend's port is: `127.0.0.1` when the development Compose runs the backend, and the Compose
network in the deployment, whose nginx does not proxy `/actuator`. A backend started from the IDE
or with `./mvnw spring-boot:run` listens on every interface of the machine, so other devices of the
same network can read the metrics too (nothing more, since the rest still needs a token).

"own" and "assigned" are checked by the service with the data of the resource, not by the role:
being authenticated or knowing an id never gives access to an incident (see `AGENTS.md`, section 6).
Listings only return the caller's incidents; reading one that is not theirs answers `404`. The
filters of `GET /incidents` apply inside that scope, so they never widen it. A `CITIZEN` may only
filter by `status` and search with `q` (#266), which only read their own reports: any other filter
answers `403`, because ignoring it would give a misleading list
and accepting `priority` would reveal a staff-only value by trying values. A photo follows the rule
of its incident: the id of an attachment is not a public URL, and a photo of an incident the caller
may not see answers `404` too (ADR-0013). A `CITIZEN` only lists and downloads the photos they
uploaded themselves: a photo that staff add while working may show a number plate, the inside of a
shop or a person, so it stays with the staff of the incident, and asking for it by its id answers
`404` like any photo the caller may not see (#284). Two accepted limits remain: a citizen who
uploads a file byte-identical to a staff photo of the same incident gets that photo's metadata back
as a retry, its `fileName` included, which is the name it had on the staff member's device (they
already hold its content, which in practice only happens when staff uploaded a photo that came from
that citizen), and the staff photos count towards
`EVIDENCE_MAX_FILES_PER_INCIDENT`, so the `422` for too many photos may tell a citizen that there
are photos they do not see, never what they show. The `Idempotency-Key` of `POST /incidents`
belongs to the citizen who sent it: the same key sent by another citizen creates an incident of
their own, so knowing someone else's key never returns their incident (#289).

## ➕ Protecting a New Endpoint

1. Add the operation to the matrix above and to `docs/api/openapi.yml` (`401`, and `403` when the role
   matters).
2. If the operation must be public, add it to `PUBLIC_API_POSTS` in `SecurityConfig` and mark it
   with `security: []` in the contract.
3. Otherwise add its rule to `ApiAuthorizationRules`: one line with the method and the path, next
   to the other operations of its resource, such as
   `auth.requestMatchers(POST, API + "/incidents/*/closure").hasAnyRole(OPERATORS)`, or
   `authenticated()` when every role may call it. Without the rule the operation answers `403` to
   everyone (deny by default). A path that another rule also matches, such as `/users/me` and
   `/users/*`, goes first. The rule is checked in the security filters, before Spring MVC reads the
   request, so the controller needs no role annotation.
4. Read the caller with `@AuthenticationPrincipal AuthenticatedUser user` and pass `user.id()` and
   `user.role()` to the service, which checks the relationship with the resource.
5. In the integration tests, `@Import(TestTokens.class)` and send `tokens.bearer(Role.X)` (or
   `tokens.bearer(userId, Role.X)` for an existing user): one test per allowed role and at least one
   for a role that gets `403`, plus one without a token (`401`). Add the operation to
   `RoleBeforeEveryOperationIT` with a refused role and an invalid input, so its `403` comes before
   the `400` or `422`, and add its row of the matrix to `RoleMatrixIT`, which calls it with every
   role. `RoleMatrixIT` compares its rows with the operations Spring MVC maps, so it fails while
   the new operation has no row.

A global `@RestControllerAdvice` must not catch `AccessDeniedException`, `AuthenticationException`
or `RequestRejectedException` (for example through a handler for `Exception`): rethrow them. A
service that refuses an action throws `AccessDeniedException` (a citizen who filters by a staff
filter), and the security filters write its `403` after the exception leaves the controller. The
firewall throws `RequestRejectedException` when Spring MVC reads a header value or a parameter name
it refuses, and its handler writes the `400`. If an advice catches either first, the client gets
`500`.

## 🚨 Security Findings

Every alert that GitHub raises on this repository gets a row here: code scanning (CodeQL) and
Dependabot. The row goes in the pull request that fixes or dismisses the alert, so the reason stays
in the repository and not only in the alert. A dismissed alert links to its row or to the section
that explains it.

A vulnerability reported privately ([security policy](../SECURITY.md)) is added only once its fix
is released: before that, a row would make it public.

| Alert | Rule | Where | Severity | Outcome | Why | Pull request |
| --- | --- | --- | --- | --- | --- | --- |
| [#1](https://github.com/Dalibex/urban-incidence-manager/security/code-scanning/1) | CodeQL `java/spring-disabled-csrf-protection` | `shared/security/SecurityConfig.java` | High | Dismissed, false positive | No credential travels in a cookie, so there is nothing to forge ([CSRF](#-csrf)) | — |
| [#2](https://github.com/Dalibex/urban-incidence-manager/security/code-scanning/2) | CodeQL `js/polynomial-redos` | `frontend/packages/api-client/src/session.ts` | High | Fixed before the merge | `/\/+$/` backtracks quadratically on a long run of slashes in the API base URL; a loop removes the trailing slashes instead | #97 |
| [#3](https://github.com/Dalibex/urban-incidence-manager/security/code-scanning/3) | CodeQL `js/incomplete-multi-character-sanitization` | `docs/architecture/drawio/generate.mjs` | High | Fixed before the merge | A regular expression stripped tags to measure the legend of each page. Not exploitable: the generator writes that text itself and only uses its length. The legend is now built from plain text, so nothing strips tags | #154 |
| [#4](https://github.com/Dalibex/urban-incidence-manager/security/code-scanning/4), [#5](https://github.com/Dalibex/urban-incidence-manager/security/code-scanning/5) | CodeQL `js/insecure-randomness` | `backend/load-tests/practice/lib.js` | High | Dismissed, used in tests | `Math.random()` places the incidents that the k6 load tests create at a random point of Málaga. CodeQL reads latitude and longitude as sensitive, but the value is test data sent to a development backend: it is no password, token or identifier, and nothing is gained by predicting it | #298 |
| Dependabot [#1](https://github.com/Dalibex/urban-incidence-manager/security/dependabot/1), [#4–#14](https://github.com/Dalibex/urban-incidence-manager/security/dependabot?q=package%3Anode-forge) | Twelve `node-forge` advisories | `frontend/pnpm-lock.yaml`, through `@expo/cli` | High | Fixed, updated to 1.4.2 | Version 1.4.0 had twelve published advisories, RSA signature verification among them; 1.4.2 is past every patched version | #158 |
| Dependabot [#2](https://github.com/Dalibex/urban-incidence-manager/security/dependabot/2) | `zod` advisory | `frontend/pnpm-lock.yaml`, through `@expo/cli` | High | Fixed, `expo` updated to 58.0.23 | `zod` 3 can exhaust memory while validating oversized collections. Only the Expo development tooling used it, and `@expo/cli` 58.1.19 no longer depends on it | #252 |
| Dependabot [#15](https://github.com/Dalibex/urban-incidence-manager/security/dependabot/15), [#17](https://github.com/Dalibex/urban-incidence-manager/security/dependabot/17) | `glob` and `minimatch` advisories | `frontend/pnpm-lock.yaml`, through Jest, ESLint and the Expo tooling | Medium | Fixed, overridden to `glob` 14 and `minimatch` 11 in `frontend/pnpm-workspace.yaml` | The fixes are only in a major version those tools do not ask for yet, so the overrides move only the vulnerable ranges up. Remove each one once Jest, ESLint and Expo require the fixed version | #252 |
| Dependabot [#18–#21](https://github.com/Dalibex/urban-incidence-manager/security/dependabot?q=package%3Anode-forge) | Four `node-forge` advisories | `frontend/pnpm-lock.yaml`, through `@expo/cli` | Medium | Fixed, updated to 1.4.3 | Two crashes of the TLS client, a ReDoS in the header parsing of `forge.http` and an Ed25519 signature check; only the Expo development tooling uses `node-forge`, not the apps | #248 |
| Dependabot [#3](https://github.com/Dalibex/urban-incidence-manager/security/dependabot/3) | `deepmerge` advisory | `frontend/pnpm-lock.yaml`, through `jest-config` | Low | Dismissed, tolerable risk | `mergeObject()` can poison the prototype of the object it returns. Only Jest uses it, to merge its own configuration when it runs the mobile tests on a developer machine or in CI: no app bundle and no input from users reach it. There is no patched version | #294 |
