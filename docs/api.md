# REST API contract

> Contract: [`docs/api/openapi.yml`](api/openapi.yml). Decision: [ADR-0008](adr/0008-backend.md) (contract first).

## 1. What it is and why it comes first

`docs/api/openapi.yml` is an OpenAPI 3.1 document and the **source of truth of the API**:

- The **backend** implements what the contract says, not the other way round.
- The **web and mobile clients** generate their types (and mocks) from it, so they can start a feature before the backend endpoint exists.
- **Errors and security** are defined once, in `components`, and every endpoint reuses them.

Any change to public behaviour (a new endpoint, a new field, a different status code) changes this file **in the same PR**. The history of `openapi.yml` is the history of the API.

## 2. What the skeleton already defines

| Part | Where | Notes |
|---|---|---|
| Header | `openapi`, `info`, `servers` | Local backend `http://localhost:8080/api/v1` and same origin `/api/v1` (Swagger UI served by the backend, or the reverse proxy proposed in [ADR-0017](adr/0017-reverse-proxy.md)). Staging is added when it exists. |
| Security | `components.securitySchemes.bearerAuth` + global `security` | JWT access token in `Authorization: Bearer <token>` ([ADR-0004](adr/0004-authentication-strategy.md)). Applies to **every** operation unless it opts out. |
| Error body | `components.schemas.Problem` (+ `FieldError`) | [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details, `application/problem+json`: `type`, `title`, `status`, `detail`, `instance`, `traceId`, and `errors` for validation failures. |
| Error responses | `components.responses` | `BadRequest` (400), `Unauthorized` (401), `Forbidden` (403), `NotFound` (404), `Conflict` (409), `InternalServerError` (500). |
| Tags | `tags` | One per backend module ([ADR-0010](adr/0010-modular-monolith.md)): `identity`, `incidents`, `geospatial`, `urbancontext`, `notifications`, `analytics`. |

There are no feature endpoints yet (`paths: {}`).

## 3. Conventions

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
- Optional means "may be absent". Use `type: [string, "null"]` only when `null` has its own meaning (for example, context not available yet).

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
| 400 | `#/components/responses/BadRequest` | The operation has a body, query parameters or path parameters to validate |
| 401 | `#/components/responses/Unauthorized` | Every protected operation |
| 403 | `#/components/responses/Forbidden` | The operation depends on the role or on the relationship with the resource |
| 404 | `#/components/responses/NotFound` | The path has an identifier |
| 409 | `#/components/responses/Conflict` | The operation changes state that can conflict (status transitions, unique values) |
| 500 | `#/components/responses/InternalServerError` | Every operation |

Do not define a new error body. If an error needs extra information, add an optional property to `Problem` in its own PR.

### Pagination (pending)

There is no agreed pagination yet, so the skeleton defines no pagination parameters. The first listing endpoint must bring the decision to the team. Proposal, based on what Spring Data already supports:

- Query parameters `page` (0-based, default 0), `size` (default 20, maximum 100) and `sort` (`property,asc|desc`).
- Response with `content` and a `page` object (`number`, `size`, `totalElements`, `totalPages`).

Once agreed, add them as `components.parameters` and a reusable schema, and update this section.

## 4. How to add an endpoint

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
           Returns the incident if the user is allowed to see it.
           Roles: any authenticated user (citizens only see their own incidents).
         parameters:
           - $ref: "#/components/parameters/IncidentId"
         responses:
           "200":
             description: The incident.
             content:
               application/json:
                 schema:
                   $ref: "#/components/schemas/IncidentResponse"
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
5. **Preview it**: run the backend and open `http://localhost:8080/swagger-ui.html`, or paste the file into [Swagger Editor](https://editor.swagger.io/). Running `npx @redocly/cli@2.71.0 preview` also works.
6. **Open the PR with the contract change first** if the frontend needs it soon (tick *OpenAPI contract* in the issue). The implementation can come in the same PR or right after, but the code must never expose something the contract does not describe.
7. **Implement the controller** following the contract, and add HTTP tests that check status codes, validation errors and permissions (AGENTS.md, section 9).

## 5. Viewing the documentation

The backend serves the contract with [springdoc-openapi](https://springdoc.org/):

| URL | What it shows |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI rendering `docs/api/openapi.yml` (the contract) |
| `http://localhost:8080/openapi.yml` | The contract file itself, copied into the jar at build time |
| `http://localhost:8080/v3/api-docs` | What springdoc generates from the controllers. Useful to compare with the contract while implementing |

The build copies `docs/api/openapi.yml` into the jar (`maven-resources-plugin` in `pom.xml`), so the documentation always matches the contract of that commit. Any future Docker build must keep `docs/api/` in its context for the same reason.

These URLs are open without authentication. To turn off Swagger UI and the generated description in an environment, set `SPRINGDOC_SWAGGER_UI_ENABLED=false` and `SPRINGDOC_API_DOCS_ENABLED=false`. `/openapi.yml` stays available: it is the public contract the clients are built from.
