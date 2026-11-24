# 🔌 API Guide

The conventions of the UrbanPulse REST API and how to change it. The API is designed contract
first: [`docs/api/openapi.yml`](api/openapi.yml) is its source of truth
([ADR-0008](adr/0008-backend.md)).

> [!NOTE]
> **For** anyone who adds or changes an endpoint, or builds a client on the API. Who may call each
> operation is in the [security guide](security.md#-permission-matrix); how the HTTP tests check the
> contract, in the [testing guide](testing.md).

## 📑 Contents

- **[What It Is and Why It Comes First](#-what-it-is-and-why-it-comes-first)**: why the contract is the source of truth of the API.
- **[What the Contract Defines](#-what-the-contract-defines)**: its parts, and the operations agreed in each sprint.
- **[Conventions](#-conventions)**: paths, names, schemas, errors and their codes, request id, retries of a report and listings.
- **[How to Add an Endpoint](#-how-to-add-an-endpoint)**: the steps, from the contract to the tests.
- **[Viewing the Documentation](#-viewing-the-documentation)**: Swagger UI and the contract served by the backend.
- **[Generated Client](#-generated-client)**: the TypeScript client of the apps, generated from the contract.

## 🎯 What It Is and Why It Comes First

`docs/api/openapi.yml` is an OpenAPI 3.1 document and the **source of truth of the API**:

- The **backend** implements what the contract says, not the other way round.
- The **web and mobile clients** generate their types (and mocks) from it, so they can start a feature before the backend endpoint exists.
- **Errors and security** are defined once, in `components`, and every endpoint reuses them.

Any change to public behaviour (a new endpoint, a new field, a different status code) changes this file **before or in the same PR as the code**, never after it. The history of `openapi.yml` is the history of the API.

## 📜 What the Contract Defines

| Part | Where | Notes |
|---|---|---|
| Header | `openapi`, `info`, `servers` | Local backend `http://localhost:8080/api/v1` and same origin `/api/v1` (Swagger UI served by the backend, or the reverse proxy in [ADR-0017](adr/0017-reverse-proxy.md)). Staging is added when it exists. |
| Security | `components.securitySchemes.bearerAuth` + global `security` | JWT access token in `Authorization: Bearer <token>` ([ADR-0004](adr/0004-authentication-strategy.md)). Applies to **every** operation unless it opts out. |
| Error body | `components.schemas.Problem` (+ `FieldError`) | [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details, `application/problem+json`: `type`, `title`, `status`, `code`, `detail`, `instance`, `traceId`, and `errors` for validation failures. |
| Error responses | `components.responses` | `BadRequest` (400), `Unauthorized` (401), `Forbidden` (403), `NotFound` (404), `Conflict` (409), `ContentTooLarge` (413), `UnprocessableEntity` (422), `TooManyRequests` (429), `InternalServerError` (500). |
| Listings | `components.parameters` + `components.schemas.PageMetadata` | `Page`, `Size` and `Sort` parameters and the `page` object of every paginated response ([Listings](#listings)). |
| Tags | `tags` | One per backend module ([ADR-0010](adr/0010-modular-monolith.md)): `identity`, `incidents`, `geospatial`, `urbancontext`, `notifications`, `analytics`. |

The Sprint 1 operations were agreed ahead of their implementation in #93:

| Module | Operations | Implemented in |
|---|---|---|
| `identity` | `createRegistration`, `createSession`, `refreshSession`, `deleteCurrentSession`, `getCurrentUser` | #35 |
| `incidents` | `listIncidents`, `getIncident`, `listIncidentStatusChanges` | #37 |
| `incidents` | `createIncident`, `listCategories` | #43 |

The committed Sprint 2 operations were agreed ahead in #204. Each one says `Planned: #N` in its description until the issue that implements it removes the mark:

| Module | Operations | Implemented in |
|---|---|---|
| `identity` | `createPasswordChange`, and `PASSWORD_CHANGE_REQUIRED` in `createSession` | #165 |
| `identity` | `listTechnicians` | #190 |
| `identity` | `listUsers`, `createUser`, `getUser`, `updateUser`, and `enabled` in `UserResponse` | #191 |
| `identity` | `listAuditEntries` | #41 |
| `incidents` | The staff filters of `listIncidents` | #182 |
| `incidents` | `createIncidentAssignment`, `listDepartments`, and `assignment` in `IncidentResponse` | #184 |
| `incidents` | `createIncidentClosure`, `createIncidentReopening` | #52 |
| `incidents` | `listIncidentComments`, `createIncidentComment` | #50 |
| `incidents` | `listIncidentAttachments`, `createIncidentAttachment`, `getAttachmentContent` | #192 |
| `incidents` | `listIncidentLocations` | #195 |
| `analytics` | `getIncidentReport` | #42 |

Added later, together with its implementation:

| Module | Operations | Implemented in |
|---|---|---|
| `incidents` | `getIncidentStatistics` | #177 |
| `urbancontext` | `getIncidentUrbanContext` | #306 |

The features after the MVP that change the API were agreed ahead in #301, and say `Planned: #N`
like the Sprint 2 ones:

| Module | Operations | Implemented in |
|---|---|---|
| `identity` | `createMfaEnrollment`, `createMfaVerification`, `createUserMfaReset`, and the `202` of `createSession`; `ENROLL_MFA` and `RESET_MFA` join `AuditAction` with that pull request | #57 |
| `incidents`, `geospatial` | `listDistricts`, and the `district` filter of `listIncidents`, `listIncidentLocations` and `getIncidentStatistics` | #59 |
| `incidents` | The text search `q` of `listIncidents`, `listIncidentLocations` and `getIncidentStatistics` | #266 |
| `incidents` | `icon` in `CategoryResponse`, and `categoryId` in `IncidentPosition` | #267 |

## 📏 Conventions

### Paths

- Base path `/api/v1` lives in `servers`. Paths in the contract start after it: `/incidents`, not `/api/v1/incidents`.
- Resources are **plural nouns in kebab-case**: `/incidents`, `/urban-assets`, `/incidents/{incidentId}/status-changes`.
- Path parameters in **camelCase** with the resource name: `{incidentId}`, never a bare `{id}`.
- Nest at most one level (`/incidents/{incidentId}/comments`). Deeper relations get their own top-level resource.
- Actions that are not CRUD are modelled as a sub-resource: `POST /incidents/{incidentId}/assignments`, not `POST /incidents/{incidentId}/assign`.

### operationId

`operationId` becomes the function name in the generated client, so it is mandatory, unique and in **camelCase** `verbNoun`:

| Method and path | operationId |
|---|---|
| `GET /incidents` | `listIncidents` |
| `GET /incidents/{incidentId}` | `getIncident` |
| `POST /incidents` | `createIncident` |
| `PATCH /incidents/{incidentId}` | `updateIncident` |
| `DELETE /incidents/{incidentId}` | `deleteIncident` |
| `POST /incidents/{incidentId}/comments` | `createIncidentComment` |

### Schemas and properties

- Schema names in **PascalCase**. Request bodies end in `Request` (`CreateIncidentRequest`), response bodies in `Response` (`IncidentResponse`). Public DTOs, never JPA entities.
- Properties in **camelCase**. Enum values in `UPPER_SNAKE_CASE`, the same as the database (`IN_PROGRESS`, `MUNICIPAL_OPERATOR`).
- Identifiers: `type: string, format: uuid`.
- Instants: `type: string, format: date-time`, always UTC.
- Coordinates: WGS 84 (EPSG:4326) as two properties, `latitude` (-90..90) and `longitude` (-180..180), with their ranges in the schema. Never an array, so they cannot be swapped.
- Optional means "may be absent". Use `type: [string, "null"]` only when `null` has its own meaning (for example, context not available yet). For a nullable `$ref`, write `oneOf: [{ $ref: ... }, { type: "null" }]` (see `category` in `IncidentResponse`).
- A text that may not be blank has `minLength: 1` and `pattern: "\\S"`, so the clients reject `"   "` as the server does. The server applies one rule to every text it stores (`shared.text`, #285): the `\s` of JSON Schema patterns (ECMA-262), so a text of no-break spaces such as U+00A0 is blank too; `minLength` and `maxLength` counted in characters (code points), so an emoji is one; and no U+0000, which PostgreSQL cannot store. Each one answers `422` on its field. Three exceptions: the email and the password of sign-in and the `currentPassword` of a password change are only compared, never stored, so they keep their own checks; and the maximum of a password is 72 bytes of UTF-8, not 72 characters, because bcrypt reads no more (*Sessions and Passwords* in `docs/security.md`).

### Absent and `null`

The two are different, and a client must handle each one:

| In the schema | In the response | Meaning | Example |
|---|---|---|---|
| In `required`, nullable | Always present, maybe `null` | `null` is a value: "not available", "not set yet" | `location.district`, `category`, `closedAt` |
| In `required`, nullable, hidden in some items | Always present; `null` where the caller may not see the value | "Nothing to show you": the caller cannot tell "none" from "hidden" | `reason` of a status change for a citizen |
| Not in `required`, described as *Staff only* | Absent for some callers | The caller may not see it | `priority`, `actor` of a status change |

- Unknown data is `null`, never `0`, `""` or `false` (AGENTS.md, section 3).
- A property that a role may never see (staff-only) is left out of the whole response, never sent as `null`. A value of a visible property that is hidden only in some items is sent as `null`: leaving it out would reveal that there is something hidden. The backend builds the response for the caller's role; it does not rely on the client hiding it.
- Jackson must not drop `null` globally (`spring.jackson.default-property-inclusion=non_null` would break every required nullable property). Leave staff-only properties out per response, for example with a separate DTO for staff and for citizens, or with `@JsonView`.
- Request bodies do not accept `null`: an optional property is just left out.

### Tags

Every operation has **exactly one** tag: the module that implements it. Do not create new tags; if an endpoint does not fit any module, discuss it with the team first (ADR-0010).

### Security

Every operation is protected by `bearerAuth` by default. A public operation (login, sign-up, token refresh) says so explicitly:

```yaml
security: []
```

Write the roles that may call the operation in its `description` (for example `Roles: MUNICIPAL_OPERATOR, ADMINISTRATOR.`). The backend enforces them; the contract documents them.

### Errors

Every operation lists the shared responses that can really happen, with `$ref`:

| Status | Reference | When to list it |
|---|---|---|
| 400 | `#/components/responses/BadRequest` | The request can be malformed: a body that is not JSON or not valid multipart, a value of the wrong type, a parameter out of range (`size` over 100, an unknown `sort` property, an identifier that is not a UUID) |
| 401 | `#/components/responses/Unauthorized` | Every protected operation |
| 403 | `#/components/responses/Forbidden` | The operation depends on the role or on the relationship with the resource |
| 404 | `#/components/responses/NotFound` | The path has an identifier |
| 409 | `#/components/responses/Conflict` | The operation changes state that can conflict (status transitions, stale version, unique values) |
| 413 | `#/components/responses/ContentTooLarge` | The operation receives a file with a size limit |
| 422 | `#/components/responses/UnprocessableEntity` | The operation has a body whose data is validated (blank title, coordinates out of range, short password), or query values that are readable but not valid together (a report period over 366 days); `errors` lists every invalid field |
| 429 | `#/components/responses/TooManyRequests` | The operation is rate limited (`createSession`, `createPasswordChange`, `createRegistration`, `createMfaVerification` and `listPlaces`) |
| 500 | `#/components/responses/InternalServerError` | Every operation |

`400` means "I cannot read this request"; `422` means "I can read it, but the data is not valid". A form shows `422` errors next to each field; a `400` is a client bug.

When an operation returns an error with a specific `code`, it describes that response inline, with its own example, instead of the shared `$ref` (see `createSession`).

Messages are always in English (`spring.web.locale-resolver: fixed`): bean validation would otherwise write them in the language of the machine that runs the backend. A client that shows them in another language keys its own texts by the `code` of the problem and the `field`, never by the text of `message`.

Do not define a new error body. If an error needs extra information, add an optional property to `Problem` in its own PR.

### Error codes

`code` is optional in `Problem`. It is added when the status alone does not tell the client what to do, and once published it never changes its meaning. Clients branch on `code`, never on the text of `detail`.

| Code | Status | Operation | Meaning |
|---|---|---|---|
| `MALFORMED_REQUEST` | 400 | Any | The request cannot be read |
| `VALIDATION_FAILED` | 422 | Any with a body, and `getIncidentReport` | The data is not valid; see `errors` |
| `EMAIL_ALREADY_EXISTS` | 409 | `createRegistration`, `createUser` | An account with this email exists |
| `INVALID_CREDENTIALS` | 401 | `createSession`, `createPasswordChange` | Wrong email or password (the same for both, so emails are not revealed) |
| `ACCOUNT_DISABLED` | 403 | `createSession`, `createPasswordChange`, `createMfaVerification` | Right credentials, disabled account |
| `TOO_MANY_ATTEMPTS` | 429 | `createSession`, `createRegistration`, `createPasswordChange`, `createMfaVerification`, `listPlaces` | Rate limited; see `Retry-After` |
| `INVALID_REFRESH_TOKEN` | 401 | `refreshSession` | Unknown, expired, revoked or reused refresh token, or a staff session opened without the second factor: sign in again |
| `INVALID_TRANSITION` | 409 | Lifecycle actions (Sprint 2) | The transition is not allowed (ADR-0020) |
| `CONCURRENT_MODIFICATION` | 409 | Lifecycle actions and `updateIncident` (Sprint 2) | The incident changed since the client read its `version`: reload it and decide again (ADR-0020) |
| `PASSWORD_CHANGE_REQUIRED` | 403 | `createSession` | Right credentials, but the password must change first |
| `CLASSIFICATION_NOT_ALLOWED` | 409 | `updateIncident` | The incident is `REJECTED` or `CLOSED` |
| `ATTACHMENT_NOT_ALLOWED` | 409 | `createIncidentAttachment` | A citizen can only upload while the incident is `REPORTED` |
| `FILE_TOO_LARGE` | 413 | `createIncidentAttachment` | The file is over the size limit |
| `IDEMPOTENCY_KEY_REUSED` | 422 | `createIncident` | The citizen already used this `Idempotency-Key` for a different report: send a new key |
| `INVALID_MFA_CHALLENGE` | 401 | `createMfaEnrollment`, `createMfaVerification` | The sign-in challenge is unknown, expired or used: sign in again |
| `INVALID_MFA_CODE` | 401 | `createMfaVerification` | The code is wrong, outside its time window or already used: type another one (five per challenge) |
| `MFA_ALREADY_ENROLLED` | 409 | `createMfaEnrollment` | The account already has a second factor: send a code instead |
| `GEOCODING_UNAVAILABLE` | 503 | `listPlaces` | The address search provider is down or too slow: place the pin by hand |

Add a row in the same PR that introduces a new code.

### Request id

Every response carries an `X-Request-ID` header (`components.headers.XRequestId`), and every `Problem` repeats it as `traceId`. The backend writes the same id at the start of every log line of the request, so a user who reports an error with its `traceId` leads straight to its log lines (Twelve-Factor XI).

- A client may send its own `X-Request-ID`, for example to follow one action across its retries. It is kept only when it has 1 to 64 letters, digits, `.`, `_` or `-`; any other value (spaces, line breaks, too long, empty) is replaced by a generated id, and is never echoed or logged.
- Without one, the backend generates 32 hexadecimal characters.
- The shared error responses list the header. Operations do not repeat it in their success responses: the `info` of the contract says it applies to all of them.
- Every response with a status of `400` or more writes one log line with its status, method and path (`Answered 422 to POST /api/v1/registrations`), so even a client error that logs nothing else can be found by its `traceId`. The line never has the body, the query string or the headers, which may hold personal data.
- `RequestIdFilter` sets it before the security filters run, so their `401` and `403`, and the `400` of a request their firewall refuses (a path with `//`, `;` or `%2F`), carry it too. In the code, a module never reads or writes it: `ApiExceptionHandler` and `SecurityProblemResponses` add `traceId`.

### Retries of a report

`createIncident` is the one operation whose retry must not repeat its effect: a citizen on a weak
connection taps *Submit* again when the answer is lost, and a second incident would reach the
operators (#289). It takes an optional `Idempotency-Key` header
(`components.parameters.IdempotencyKey`):

- The client creates a UUID once per report and sends it unchanged on every retry of that report. A
  report whose data changed is a new report and gets a new key; a report saved successfully, too.
- A key the same citizen already used with the same body answers `201` with that incident and
  creates nothing; with another body, `422` `IDEMPOTENCY_KEY_REUSED`. Each key is kept with its
  incident for as long as the incident exists, and is unique per citizen.
- Without the header every request creates an incident. No other operation takes the header.

### Domain errors

An entity never knows the HTTP status of its errors (ADR-0020, `AGENTS.md`). It throws one of the two kinds of `shared.error`, with its stable `code`, and `ApiExceptionHandler` decides the status:

| Kind | Status | Example |
|---|---|---|
| `RuleViolationException` | 409 | `INVALID_TRANSITION`: the transition table does not allow the change |
| `InvalidDataException` | 422 `VALIDATION_FAILED` | A reason, a department or a priority justification is missing; one `FieldError` per field, the same shape as bean validation |

Errors of the services that are not domain rules (an incident that does not exist, too many attempts) still extend `shared.web.ApiProblemException`, which carries its status.

### Listings

Every listing is paginated, so no query runs without a limit (AGENTS.md, section 6):

- **Parameters** `page` (0-based, default 0), `size` (default 20, maximum 100) and `sort` (`property,asc|desc`), as `#/components/parameters/Page`, `Size` and `Sort`. A `size` over 100 returns `400`; the backend never reduces it silently. So does a `page` that starts past item 2,147,483,647 (`page` times `size`), the last one a query can skip to.
- **Response** with `content` (the items) and `page` (`#/components/schemas/PageMetadata`: `number`, `size`, `totalElements`, `totalPages`). This is the shape Spring Data produces with `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`.
- **Sorting.** Each listing documents the properties it accepts and its default in its `description`. The backend always adds `id` as the last criterion, so the order is stable and no item appears on two pages.
- **Filters** use the name of the property they filter (`status`, `categoryId`, `priority`), repeatable for several values (`?status=REPORTED&status=VALIDATED`). Date ranges are `reportedFrom` / `reportedTo`, UTC instants. Only the parameters the contract declares as arrays repeat: any other one sent twice (`?page=0&page=5`) returns `400`.

Short reference lists managed by administrators, such as `listCategories`, and lists bounded by the domain, such as the photos of one incident, return a plain array without pagination. Their `description` says why. The status history of an incident and the technicians also return a plain array today, although they have no upper bound, and the map locations (`listIncidentLocations`) stop at 1,000 with a `truncated` flag: Planned: #360.

### What Spring does not do by default

The contract is stricter than Spring's defaults, and the acceptance tests follow the contract:

- **Paging.** A `Pageable` controller argument silently reduces `size` to 2000, turns invalid values into the defaults, and an unknown `sort` property ends in `500`. Receive `page`, `size` and `sort` as validated `@RequestParam`s (`@Min`, `@Max`), check `sort` against the properties the listing accepts, and build the `PageRequest` by hand with `id` as the last criterion.
- **Unknown and empty parameters.** Spring ignores a query parameter no argument asks for, so a mistyped filter returns the unfiltered list, and it turns an empty value (`?categoryId=`) into `null`. A listing that documents its parameters receives them all as `@RequestParam MultiValueMap` and checks them with `QueryParameters.requireKnownAndFilled`, which answers `400` to both. Spring also splits a single value on commas and turns an empty piece (`?status=REPORTED,`) into a `null` element of the list, so the incident filters answer `400` to it too. A typed `@RequestParam` takes only the first value of a repeated parameter, so the listing also passes the parameters that are not arrays in the contract to `QueryParameters.requireSingle`, which answers `400` when one is repeated.
- **Validation.** A failed `@Valid` body returns `400` by default. The shared `@RestControllerAdvice` translates it to `422` with `VALIDATION_FAILED` and `errors`.

## ➕ How to Add an Endpoint

An operation agreed ahead (see [What the Contract Defines](#-what-the-contract-defines)) is already in the contract: start at step 7, remove its `Planned: #N` mark in the pull request that builds it, and if you find a real difference, correct the contract in that pull request and tell the client streams. The steps below are for an operation that is not there yet.

1. **Branch from the feature issue** and edit `docs/api/openapi.yml` **before** writing the controller.
2. **Add the path** under `paths`, following the conventions above. Example:

   ```yaml
   paths:
     /incidents/{incidentId}:
       get:
         tags: [incidents]
         operationId: getIncident
         summary: Get an incident
         description: |
           The incident, if the user may see it.
           Roles: `CITIZEN` (own incidents), `TECHNICIAN` (actively assigned), `MUNICIPAL_OPERATOR`,
           `ADMINISTRATOR`. `ANALYST` gets `403`.
         parameters:
           - $ref: "#/components/parameters/IncidentId"
         responses:
           "200":
             description: The incident.
             content:
               application/json:
                 schema:
                   $ref: "#/components/schemas/IncidentResponse"
           "400":
             $ref: "#/components/responses/BadRequest"
           "401":
             $ref: "#/components/responses/Unauthorized"
           "403":
             $ref: "#/components/responses/Forbidden"
           "404":
             $ref: "#/components/responses/NotFound"
           "500":
             $ref: "#/components/responses/InternalServerError"
   ```

3. **Add the schemas and parameters** it needs under `components` (`IncidentResponse`, `IncidentId`…). Reuse existing ones before creating new ones. Add `examples` to the properties: they feed the clients' mocks.
4. **Validate it locally** from the repository root:

   ```bash
   npx @redocly/cli@2.71.0 lint
   ```

   The rules are in `redocly.yaml` and are the same ones the `contract` CI job runs.
5. **Preview it**: run the backend and open `http://localhost:8080/swagger-ui.html`, or paste the file into [Swagger Editor](https://editor.swagger.io/). Without the backend, build a static page from the repository root with `npx @redocly/cli@2.71.0 build-docs docs/api/openapi.yml -o redoc-static.html` and open it (do not commit it). `redocly preview` is not for this: it previews a whole Redocly project and parses every YAML file of the folder.
6. **Open the PR with the contract change first** if the clients or the tester need it soon (tick *OpenAPI contract* in the issue), and regenerate the client types (`pnpm generate:api`, see [Generated Client](#-generated-client)) in it. The implementation can come in the same PR or right after, but never before: the code must never expose something the contract does not describe. If the implementation needs to change what was agreed, it changes the contract in its own PR and tells the client streams.
7. **Implement the controller** following the contract, and add HTTP tests that check status codes, validation errors and permissions (AGENTS.md, section 9).

## 👀 Viewing the Documentation

The backend serves the contract with [springdoc-openapi](https://springdoc.org/):

| URL | What it shows |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI rendering `docs/api/openapi.yml` (the contract) |
| `http://localhost:8080/openapi.yml` | The contract file itself, copied into the jar at build time |
| `http://localhost:8080/v3/api-docs` | What springdoc generates from the controllers. Useful to compare with the contract while implementing |

The build copies `docs/api/openapi.yml` into the jar (`maven-resources-plugin` in `pom.xml`), so the documentation always matches the contract of that commit. The backend image does the same: `backend/Dockerfile` takes the file from a second build context, `contract` (`docs/api`), which both Compose files pass to it.

These URLs are open without authentication. To turn off Swagger UI and the generated description in an environment, set `SPRINGDOC_SWAGGER_UI_ENABLED=false` and `SPRINGDOC_API_DOCS_ENABLED=false`. `/openapi.yml` stays available: it is the public contract the clients are built from.

## 🧬 Generated Client

The clients never write the API types by hand. `frontend/packages/api-client/src/schema.d.ts` is generated from the contract; after changing `docs/api/openapi.yml`, regenerate it from `frontend/` and commit the result in the same pull request:

```bash
pnpm generate:api
```

> [!WARNING]
> Never edit `schema.d.ts` by hand: the `contract` CI job fails if it differs from the contract.

Each operation gets a typed function in `api-client` (`listIncidents`, `getIncident`...) that returns an `ApiResult`, so both apps handle failures by `status` and `code`, never by the text.
