# Changelog

Notable changes on this project will be documented in this file.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- The technician's work on the web: the *Work queue* lists the incidents assigned to them, with the filters of the inbox, and opens on the `ASSIGNED` and `IN_PROGRESS` ones. Its detail leads back to the queue, accepts an assigned incident and resolves one in progress with the resolution notes. A change made on an incident someone else changed meanwhile asks to reload it, and the notes are kept (#187).
- Assignment of an incident on the API: an operator or administrator assigns a validated or reopened incident to an active department and, optionally, an active technician and a team, or reassigns an assigned one. The previous assignment is kept, inactive, and a change made on an incident someone else changed meanwhile answers `409` with `CONCURRENT_MODIFICATION`. Staff see the active assignment in the incident detail; citizens only see that it is assigned. The active departments are listed for staff (#184).
- Filters on the incident list of the API for staff: category, priority, reporting dates and the department of the active assignment, combinable with each other and with the status, and always inside what each role may see. A citizen can only filter by status; any other filter answers `403`. Filtering by department and sorting by `updatedAt` use indexes measured on the performance dataset (#182).
- Technicians accept an assigned incident (`POST /incidents/{incidentId}/acceptance`, `ASSIGNED` to `IN_PROGRESS`) and resolve it with their notes (`POST /incidents/{incidentId}/resolution`, `IN_PROGRESS` to `RESOLVED`) on the API. Only the technician of the active assignment can act; anyone else's incident answers `404`. The citizen sees the new status, not the notes. A lifecycle reason has at most 2,000 characters (#186).
- Incident classification for operators and administrators, on the API (`PATCH /incidents/{id}`) and in the web incident detail: the category, the priority and why it was given, alone or together. Each change is kept in the audit trail with the values it replaced, and staff read it with `GET /incidents/{id}/classification-changes`. A rejected or closed incident answers `409` with the code `CLASSIFICATION_NOT_ALLOWED`, and a change made on an incident someone else changed meanwhile answers `409` with `CONCURRENT_MODIFICATION` (#47).
- Quick sign-in for the demo and development: with `VITE_QUICK_SIGN_IN` (web) or `EXPO_PUBLIC_QUICK_SIGN_IN` (mobile) set to `true`, the sign-in shows one button per demo user, using the password in `VITE_DEMO_PASSWORD` or `EXPO_PUBLIC_DEMO_PASSWORD` (`apps/web/.env.example`, `apps/mobile/.env.example`). Off by default; a build made with it on contains the password and must never be deployed (#167).
- Filters on the operator inbox of the web: one or several statuses, category, priority, department and the days the incident was reported, kept in the address with the page. The menu and the sign-in open the inbox on the `REPORTED` incidents; *Clear filters* lists them all, and *Back to the inbox* returns to the same filters (#183, #217).
- Photos of an incident on the API: upload one JPEG, PNG or WebP photo at a time, list the photos of an incident and download each one. The type is read from the content, never from the file name. Uploading the same photo again to the same incident returns the one already stored instead of a copy, so a client can retry safely. A photo over `EVIDENCE_MAX_FILE_SIZE` answers `413`, one more than `EVIDENCE_MAX_FILES_PER_INCIDENT` answers `422`, and a citizen can only add photos while the incident is `REPORTED`. Who can see a photo is who can see its incident (#192).
- Photos on the mobile app: after reporting, or from the detail while the report is `REPORTED`, the citizen adds photos from the camera or the gallery. The app makes each photo smaller (at most 1920 px on its longest side, JPEG) before uploading it, shows the upload progress, and retries a failed upload twice on its own. If it still fails, the citizen is told that the report is saved without the photo and can try again. The app asks for the camera permission only when the citizen chooses the camera (#193).
- Positions of incidents for the map on the API (`GET /incident-locations`): id, status, priority, latitude and longitude, with the same filters as the incident list and an optional bounding box. Operators and administrators get every incident and technicians their active assignments; citizens and analysts get `403`. It returns at most 1,000 positions, the highest priority and newest first, and says when it left some out (#195).

### Changed

- The incident list of the API answers `400` to an unknown query parameter or one without a value, instead of ignoring it (#182).

## [0.1.0] - 2026-11-03

End of Sprint 1: a citizen signs up on the mobile app, reports an incident with its position and sees it in their list, and an operator signs in on the web app and sees it in the inbox and its detail. The web app still runs only with the Vite development server.

### Added

- Backend container image (`backend/Dockerfile`), started next to the database with `docker compose --profile app up` and published on `127.0.0.1`, port `BACKEND_PORT` (8080 by default) (#90).
- Citizen sign-up, sign-in, session refresh and sign-out on the API, with access tokens and rate-limited attempts (#35).
- Sign-in on the web and the mobile app, citizen sign-up on the mobile app, transparent session refresh and sign-out. Each role lands on its own web screen and cannot open the screens of another role; citizens are sent to the mobile app. The mobile app needs `EXPO_PUBLIC_API_BASE_URL` (`apps/mobile/.env.example`) (#39).
- Incident reporting on the mobile app: title, description, an optional category and the GPS position of the phone, with its accuracy (#43).
- Incident list, detail and status history on the API. Citizens see only their incidents, technicians only those assigned to them, and operators and administrators all of them; anyone else's incident answers `404`. Citizens do not receive priority, actors or internal reasons (#100).
- The incident detail gives staff its `version`. Lifecycle actions will send it back, and a change made on an incident someone else changed meanwhile answers `409` with the code `CONCURRENT_MODIFICATION` instead of overwriting it (#101).
- Initial incident categories and departments, loaded by a database migration in every environment (#34).
- Base map of Málaga built from OpenStreetMap with `docker compose --profile tiles run --rm tiles-builder`, with its own style, fonts and sprites, so the maps need no tile provider and no API key (#38).
- Operator inbox on the web, for operators and administrators: every incident, newest first and page by page, and an incident detail with the report, its location ("Not available" when the address or district is unknown) and the full history, internal entries included (#46).
- *My reports* on the mobile app: the citizen's own incidents, open or closed, newest first and loaded as the list scrolls, and a detail with its location ("Not available" when the address or district is unknown) and the history visible to the citizen, where an information request shows as a message from the city council. Pull down to refresh; signing out forgets the loaded incidents (#45).
- Development dataset behind the `dev` profile, and `SPRING_PROFILES_ACTIVE` to choose the data profile of the backend container: empty for no sample data, `dev`, `demo` or `perf` (#83).
- Performance dataset behind the `perf` profile, for the quality attribute measurements: 100,000 incidents by default (`PERF_INCIDENTS`) with their users, history, assignments, comments, attachment metadata and notifications, generated by PostgreSQL with a fixed seed so every load gives the same rows (#84).
- Demo dataset behind the `demo` profile: one user per role, whose password comes from `DEMO_PASSWORD`, and fifteen incidents in Málaga in every status. `docs/demo.md` is the demo script (#54).
- One-off process that creates the first administrator of a clean deployment from `BOOTSTRAP_ADMIN_NAME`, `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`: `docker compose --profile app run --rm backend --spring.profiles.include=bootstrap-admin`. It changes nothing when an administrator already exists (#126).

### Changed

- Every `/api/v1` call needs a Bearer access token, except sign-up, sign-in and session refresh (#35).
- The backend requires the `JWT_SECRET` environment variable, and accepts `JWT_ACCESS_TOKEN_TTL` and `REFRESH_TOKEN_TTL` (#35).
- The backend reads the evidence storage settings `STORAGE_BASE_PATH` (`/app/storage` by default), `EVIDENCE_MAX_FILE_SIZE` (`10MB`) and `EVIDENCE_MAX_FILES_PER_INCIDENT` (`5`), and the container keeps the files in the `evidence_data` volume. Uploading evidence is not available yet (#32).
- Incident lists, their counts, the technician's view and the history use indexes measured on the performance dataset: the operator inbox page goes from about 111 ms to under 1 ms with 100,000 incidents (#132).
- Every API response carries an `X-Request-ID` header, every error body repeats it as `traceId`, and the backend writes it in the log lines of the request, so a reported error can be found in the logs. A client may send its own id; an unsafe one is replaced. Every error response writes one log line with its status, method and path, and validation messages are always in English, whatever the client's `Accept-Language` or the language of the machine that runs the backend (#131).
- Sending a refresh token that was already used or signed out revokes every session of its user, in case someone else holds a copy. Expired refresh tokens are deleted every day once `REFRESH_TOKEN_RETENTION` (30 days by default) has passed since they expired (#125).

## [0.0.1] - 2026-10-26

Application skeleton built on the `boilerplate` branch.

### Added

- Spring Boot backend in `backend/` with Spring Security, where only the Actuator health endpoints and the API documentation are open.
- PostgreSQL with PostGIS for local development through Docker Compose, configured with `.env` (`.env.example`).
- Flyway migrations V1–V5 with the full data model, and its diagram in `docs/database.drawio.svg` (#9).
- Empty module packages (`identity`, `incidents`, `urbancontext`, `geospatial`, `notifications`, `analytics`) and the `shared` package, each with its `package-info.java`.
- OpenAPI 3.1 contract skeleton in `docs/api/openapi.yml`: servers, global `bearerAuth` (JWT), RFC 9457 `Problem` schema, reusable 400/401/403/404/409/500 responses and one tag per module; conventions in `docs/api.md`; Swagger UI with springdoc and `contract` CI job (#13).
- Client pnpm workspace in `frontend/`: web app (React + Vite), mobile app (React Native + Expo) and the shared `domain`, `ui` and `api-client` packages (#16).
- CI pipeline with the `lint`, `contract`, `frontend`, `test`, `build` and `pr-title` jobs, and a pre-commit hook that formats staged Java and client files.
- Integration tests with Testcontainers, a JaCoCo coverage gate and SpotBugs.
- ADRs 0001–0019, the C4 model in `docs/architecture/`, the development and CI guides, `CONTRIBUTING.md`, `SECURITY.md` and issue and pull request templates.
