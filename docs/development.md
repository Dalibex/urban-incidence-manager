# 🛠️ Development Guide

This guide explains how to run the UrbanPulse backend and clients locally and start working on them.

## 🐛 Prerequisites

Install:

- Java 25
- Docker Engine or Docker Desktop
- Docker Compose v2
- Git
- Node.js 24 and pnpm, for the web and mobile clients (see the *Client Workspace* section)
- IntelliJ IDEA (optional, recommended)

Maven does not need to be installed globally. The project includes Maven Wrapper.

## 📥 Get the Code

Clone the repository and enter the backend directory:

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/backend
```

## ⚙️ Create the Local Environment File

Docker Compose reads the database settings from `backend/.env`. Without this file the variables
are empty and PostgreSQL does not start. Create it from the versioned example:

Linux or macOS:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Replace `POSTGRES_PASSWORD` in `.env`. Never commit `.env`; only `.env.example` is versioned.

Change `POSTGRES_PORT` (5432) or `BACKEND_PORT` (8080) only if something else on your computer
already uses that port. `BACKEND_PORT` is only used when the backend runs in Docker (see *Run the
Backend in Docker*); from the IDE the backend always listens on 8080.

PostgreSQL applies `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` only when it initializes
an empty volume. If you change them later, see the *Reset the Local Database* section below.

## 🍃 Run with IntelliJ IDEA (recommended)

1. Select **Open** and choose the `backend/` directory.
2. Import the project as a Maven project if IntelliJ does not detect it automatically.
3. Select Java 25 as the project SDK.
4. Make sure Docker is running.
5. Run `UrbanpulseApplication` from `src/main/java/es/uma/urbanpulse/UrbanpulseApplication.java`.

Spring Boot detects `docker-compose.yaml`, starts PostgreSQL/PostGIS, waits until it is healthy, and
configures the connection. Stopping the application also stops the database container it started.

## 🍂 Run from the Terminal

From `backend/`:

Linux or macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

Stop the application with `Ctrl+C`.

## 🐳 Run the Backend in Docker

The backend also runs as a container, from the same image that the CI tests and that is deployed
([ADR-0011](adr/0011-deployment-12factor.md)). Use it to check that the backend works outside your
IDE, or when you work on the clients and only need the API. You do not need Java or Maven for this:
the image compiles the jar.

From `backend/`, with `.env` created as above:

```bash
docker compose --profile app up --build
```

This starts the database and the backend. The API listens on `http://localhost:8080`, or on
`BACKEND_PORT` if you set it in `.env`. Check it with:

Linux or macOS:

```bash
curl http://localhost:8080/actuator/health
```

Windows PowerShell (`curl.exe`, because in Windows PowerShell `curl` is an alias of
`Invoke-WebRequest`):

```powershell
curl.exe http://localhost:8080/actuator/health
```

- `--build` rebuilds the image with your current code. Without it, Compose reuses the last image it
  built. The first build takes a few minutes because it downloads Maven and the dependencies; later
  builds reuse them.
- Those downloads and the image layers stay in Docker's build cache, which grows with every build
  that changes something. `docker system df` shows its size (the *Build Cache* line). To free the
  space, run `docker builder prune`: it does not touch images, containers or volumes, but the next
  build takes as long as the first one.
- The `backend` service belongs to the `app` profile. Without `--profile app`, Compose and the IDE
  start only the database, so both ways of running the backend keep working. Do not run both at the
  same time: they use the same port.
- The port is published on `127.0.0.1`, so the API is only reachable from your computer: the web
  app, `curl` or an Android emulator (`http://10.0.2.2:8080`). A physical phone cannot reach it;
  to test the mobile app on a phone, run the backend from the IDE or the terminal instead (see
  *Client Workspace*).
- With `-d` (in the background), follow the logs with `docker compose logs -f backend`.
- Stop everything with `Ctrl+C`, or with `docker compose --profile app down` if you started it with
  `-d`. Your data stays in the `postgres-data` volume.

## 🚀 Running Tests

From `backend/`:

Linux or macOS:

```bash
./mvnw verify
```

Windows PowerShell:

```powershell
./mvnw.cmd verify
```

Docker must be running. Integration tests do not use `docker-compose.yaml`: they start their own
PostGIS container with Testcontainers (`TestcontainersConfiguration`), so they never touch your
local data.

Test classes are picked up by name:

- `*Test`: unit tests, no Docker, run by `./mvnw test`.
- `*IT`: integration tests with the Spring context and PostGIS, run by `./mvnw verify`. Skip them
  with `-DskipITs`.

`verify` also checks formatting, coverage and SpotBugs. See the [CI guide](ci.md).

## 🐧 Code Formatting

Java code is formatted with google-java-format (AOSP style) through Spotless. Any local `./mvnw`
build installs a pre-commit hook that formats the staged Java files, so commits are already
formatted. To format by hand, if needed:

```bash
./mvnw spotless:apply
```

If the hook does not run, see the [CI setup guide](ci-setup.md).

## 🗄️ Database Migrations

The schema is created and changed only by Flyway ([ADR-0014](adr/0014-database-migrations-flyway.md)).
Migrations are plain SQL files in `src/main/resources/db/migration` and run automatically when the
application starts. Hibernate only validates the entities against the schema.

- Name new files `V<n>__<module>_<description>.sql`, with the next free number, for example
  `V6__incidents_add_incident_source.sql`.
- Never edit a migration that has been merged: write a new one, even for a fix.
- If two branches use the same number, the second one to merge renumbers its migration.
- Update the [database schema diagram](database.drawio.svg) in the same pull request.

While a migration only exists on your branch, you can still change it: reset your local database
(see below) so Flyway applies it again from scratch.

## ⚠️ Reset the Local Database

`docker compose down` stops the database and keeps its data in the `postgres-data` volume. To
delete all local data and start from an empty database:

```bash
docker compose --profile app down -v
```

`--profile app` also removes the backend container, if you started it.

## 📱 Client Workspace

The web and mobile clients live in a pnpm workspace in `frontend/` ([ADR-0007](adr/0007-client-application-architecture.md)):

```text
frontend/
|-- apps/
|   |-- web/           React + Vite (ADR-0006): operators, technicians, analysts, administrators
|   `-- mobile/        React Native + Expo (ADR-0005): citizens
|-- packages/
|   |-- domain/        Domain types shared by both apps
|   |-- ui/            Design tokens and platform-agnostic presentation helpers
|   `-- api-client/    Typed client generated from docs/api/openapi.yml
|-- package.json       Workspace scripts and the pnpm version (packageManager)
|-- pnpm-workspace.yaml
`-- .nvmrc             Node version
```

Apps import the shared packages by name (`@urbanpulse/domain`, `@urbanpulse/ui`,
`@urbanpulse/api-client`), as source: there is no separate build step for the packages.

### Setup

Use the Node version in `.nvmrc` (24) and the pnpm version in `packageManager`. Corepack, which
ships with Node, installs that exact pnpm version:

```bash
corepack enable
```

Then, from `frontend/`:

```bash
cd frontend
```

```bash
pnpm install
```

Always use `pnpm`, never `npm install` or `yarn`: only `pnpm-lock.yaml` is versioned.

### Run the apps

From `frontend/`:

```bash
pnpm dev:web
```

The web app opens at `http://localhost:5173`.

```bash
pnpm dev:mobile
```

Expo prints a QR code. Scan it with the **Expo Go** app (Android or iOS) on a phone in the same
network, or press `a` / `i` to open an Android emulator or iOS simulator. If the phone cannot
connect (firewall, isolated Wi-Fi), run `pnpm dev:mobile --tunnel`. The mobile app only targets
Android and iOS (`platforms` in `app.json`); the browser client is `apps/web`. A phone or emulator
cannot reach the backend through `localhost`: when the apps call the API, configure its URL with
the address of your machine in the local network.

### Checks

The same checks as the `frontend` CI job, from `frontend/`:

```bash
pnpm format:check
```

```bash
pnpm lint
```

```bash
pnpm typecheck
```

```bash
pnpm test
```

```bash
pnpm build
```

`pnpm format` fixes the formatting; the pre-commit hook also formats the staged client files.

### API client

`frontend/packages/api-client/src/schema.d.ts` is generated from the contract. After changing
`docs/api/openapi.yml`, regenerate it from `frontend/` and commit the result in the same pull request:

```bash
pnpm generate:api
```

Never edit `schema.d.ts` by hand: the `contract` CI job fails if it differs from the contract.

## 🔙 Backend Structure

```text
backend/
|-- .env.example
|-- docker-compose.yaml
|-- pom.xml
|-- mvnw
|-- mvnw.cmd
`-- src/
    |-- main/java/es/uma/urbanpulse/
    |   |-- UrbanpulseApplication.java
    |   |-- identity/        users, roles, login, JWT and refresh tokens, audit log
    |   |-- incidents/       incidents, location, categories, assignments, comments, attachments
    |   |-- urbancontext/    external data (Open Data Málaga, OSM, Open-Meteo, TomTom), enrichment
    |   |-- geospatial/      districts, neighbourhoods, urban assets, spatial queries (PostGIS)
    |   |-- notifications/   notifications and their delivery (Brevo, Twilio)
    |   |-- analytics/       read-only indicators and reports
    |   `-- shared/          cross-cutting configuration, not a module
    |       `-- security/    SecurityConfig (Spring Security filter chain)
    |-- main/resources/application.yaml
    |-- main/resources/db/migration/
    `-- test/java/es/uma/urbanpulse/
```

Each module follows the same plain MVC layout: `controller/`, `dto/`, `entity/`, `mapper/`,
`repository/`, `service/`, plus `client/` when it calls an external system.

Modules are packages for organisation only ([ADR-0010](adr/0010-modular-monolith.md)). Any class may
use another module's services or entities.

Every package has a `package-info.java` that says what it does and which tables it owns.

- Application code: `src/main/java/es/uma/urbanpulse/`.
- Configuration: `src/main/resources/`.
- Database migrations: `src/main/resources/db/migration/`.
- Tests: `src/test/java/es/uma/urbanpulse/`.
