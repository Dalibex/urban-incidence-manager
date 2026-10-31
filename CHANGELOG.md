# Changelog

Notable changes on this project will be documented in this file.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Citizen sign-up, sign-in, session refresh and sign-out on the API, with access tokens and rate-limited attempts (#35).
- Sign-in on the web and the mobile app, citizen sign-up on the mobile app, transparent session refresh and sign-out. Each role lands on its own web screen and cannot open the screens of another role; citizens are sent to the mobile app. The mobile app needs `EXPO_PUBLIC_API_BASE_URL` (`apps/mobile/.env.example`) (#39).
- Incident list, detail and status history on the API. Citizens see only their incidents, technicians only those assigned to them, and operators and administrators all of them; anyone else's incident answers `404`. Citizens do not receive priority, actors or internal reasons (#100).

### Changed

- The backend requires the `JWT_SECRET` environment variable, and accepts `JWT_ACCESS_TOKEN_TTL` and `REFRESH_TOKEN_TTL` (#35).

### Fixed

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
