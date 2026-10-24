# 🛠️ Development Guide

This guide explains how to run the UrbanPulse backend locally and start working on it.

## 🐛 Prerequisites

Install:

- Java 25
- Docker Engine or Docker Desktop
- Docker Compose v2
- Git
- IntelliJ IDEA (optional, recommended)

Maven does not need to be installed globally. The project includes Maven Wrapper.

## 📥 Get the Code

Clone the repository and enter the backend directory:

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/urbanpulse
```

## ⚙️ Create the Local Environment File

Docker Compose reads the database settings from `urbanpulse/.env`. Without this file the variables
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

PostgreSQL applies `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` only when it initializes
an empty volume. If you change them later, see the *Reset the Local Database* section below.

## 🍃 Run with IntelliJ IDEA (recommended)

1. Select **Open** and choose the `urbanpulse/` directory.
2. Import the project as a Maven project if IntelliJ does not detect it automatically.
3. Select Java 25 as the project SDK.
4. Make sure Docker is running.
5. Run `UrbanpulseApplication` from `src/main/java/es/uma/urbanpulse/UrbanpulseApplication.java`.

Spring Boot detects `docker-compose.yaml`, starts PostgreSQL/PostGIS, waits until it is healthy, and
configures the connection. Stopping the application also stops the database container it started.

## 🍂 Run from the Terminal

From `urbanpulse/`:

Linux or macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

Stop the application with `Ctrl+C`.

## 🚀 Running Tests

From `urbanpulse/`:

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
docker compose down -v
```

## 🔙 Backend Structure

```text
urbanpulse/
|-- .env.example
|-- docker-compose.yaml
|-- pom.xml
|-- mvnw
|-- mvnw.cmd
`-- src/
    |-- main/java/es/uma/urbanpulse/
    |-- main/resources/application.yaml
    |-- main/resources/db/migration/
    `-- test/java/es/uma/urbanpulse/
```

- Application code: `src/main/java/es/uma/urbanpulse/`.
- Configuration: `src/main/resources/`.
- Database migrations: `src/main/resources/db/migration/`.
- Tests: `src/test/java/es/uma/urbanpulse/`.
