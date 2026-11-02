# 🏙️ UrbanPulse

UrbanPulse is a platform for managing urban incidents reported by citizens and enriching them with contextual data from the city of Malaga.

> **Status:** Early development (Sprint 1). Citizens can sign up, report an incident from the mobile app and follow their reports; operators see the incidents in a web inbox. Validating, assigning and resolving incidents, evidence, notifications and urban context are not implemented yet.

## 🎯 Purpose

UrbanPulse aims to support the registration, classification, prioritization, tracking, analysis, and resolution of urban incidents.

The platform assists municipal staff. It does not replace human decisions for sensitive actions.

## 📦 Current Scope

The repository currently provides:

- **Authentication:** sign up, sign in, refresh and sign out with JWT access tokens and refresh tokens, and a role check on every operation ([security guide](docs/security.md)).
- **Reference data:** the initial categories and departments, inserted by a migration.
- **Incidents:** reporting with a position, listing, detail and status history, filtered by role, through the ten operations of the OpenAPI 3.1 contract (`docs/api/openapi.yml`, served with Swagger UI). The lifecycle service applies the transitions of [ADR-0020](docs/adr/0020-incident-lifecycle-transitions.md) with optimistic locking, and an append-only audit trail records the relevant actions.
- **Clients:** a pnpm workspace in `frontend/` with the web app for staff (operator inbox and incident detail), the mobile app for citizens (report an incident, "My reports") and their shared packages, including the API client generated from the contract.
- **Data:** the database schema of the whole data model, created by Flyway migrations, and sample data behind the `dev`, `demo` and `perf` profiles ([datasets](docs/datasets.md)).
- **Base map:** a self-hosted OpenStreetMap map of the province of Málaga, built as a one-off process ([ADR-0016](docs/adr/0016-self-hosted-osm-base-map.md)).
- **Operations:** Docker Compose for the database and a container image of the backend, integration tests against PostGIS with Testcontainers, a GitHub Actions pipeline and a pre-commit hook that formats staged Java and client files.

## 🧰 Technology Stack

- Java 25
- Spring Boot 4.1.1
- Spring Data JPA
- Spring Security
- PostgreSQL 18 with PostGIS 3.6
- Flyway
- Docker Compose
- Testcontainers
- GitHub Actions
- Maven Wrapper
- TypeScript, React and Vite (web)
- React Native and Expo (mobile)
- Node.js 24 and pnpm workspaces

## 🐛 Prerequisites

Install Java 25, Docker Engine or Docker Desktop, and Git. Maven and PostgreSQL do not need to be installed locally. To work on the web or mobile clients, also install Node.js 24; pnpm is provided by Corepack (see the [development guide](docs/development.md)).

When running the project from a terminal, make sure Java 25 is available in `PATH` and `JAVA_HOME` points to the Java 25 JDK.

## ⚡ Quick Start

Clone the repository and enter the backend directory:

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/backend
```

### ⚙️ Create the local environment file

Linux or macOS:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Edit `.env`:

- Replace `POSTGRES_PASSWORD`.
- Fill in `JWT_SECRET`, the secret that signs the access tokens, at least 32 bytes. The backend does not start without it. Generate one with `openssl rand -base64 48` (on Windows, from Git Bash).

Write the values without quotes. Every variable is described in the [deployment guide](docs/deployment.md).

### 🍂 Run the application

Linux or macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

Spring Boot detects `docker-compose.yaml`, starts PostgreSQL/PostGIS, waits until the database is healthy, and configures the connection. Check that it is up at `http://localhost:8080/actuator/health`; the API documentation is at `http://localhost:8080/swagger-ui.html`.

The database starts empty. To try the API and the clients with sample users and incidents, start the backend with the `dev` profile (see the [development guide](docs/development.md)), and to run the web and mobile apps, see *Client Workspace* in the same guide.

To run the backend as a container instead, with the same image as the CI, use `docker compose --profile app up --build` (see the [deployment guide](docs/deployment.md)).

## 🚀 Testing

Docker must be running: the integration tests start their own PostGIS container with Testcontainers.

Linux or macOS:

```bash
./mvnw verify
```

Windows PowerShell:

```powershell
./mvnw.cmd verify
```

## 🗂️ Project Structure

```text
urban-incidence-manager/
|-- .githooks/
|-- .github/
|-- backend/
|   |-- Dockerfile
|   |-- docker-compose.yaml
|   |-- pom.xml
|   |-- mvnw
|   |-- mvnw.cmd
|   `-- src/
|-- docs/
|   |-- adr/
|   |-- api/
|   |-- architecture/
|   |-- api.md
|   |-- ci.md
|   |-- database.md
|   |-- datasets.md
|   |-- demo.md
|   |-- deployment.md
|   |-- development.md
|   |-- security.md
|   `-- testing.md
|-- frontend/
|   |-- apps/
|   |   |-- web/
|   |   `-- mobile/
|   |-- packages/
|   |   |-- api-client/
|   |   |-- domain/
|   |   `-- ui/
|   |-- package.json
|   `-- pnpm-workspace.yaml
|-- tiles/                base map builder (ADR-0016)
|-- AGENTS.md
|-- CHANGELOG.md
|-- CODE_OF_CONDUCT.md
|-- CONTRIBUTING.md
|-- LICENSE
|-- README.md
`-- SECURITY.md
```

## 🗄️ Database Schema

Data model created by the Flyway migrations. Click the image to open it at full size.

[![UrbanPulse database schema](docs/database.drawio.svg)](docs/database.drawio.svg)

The diagram is a `.drawio.svg`: GitHub shows it as an image, and draw.io opens it as an editable diagram. The Flyway migrations in `backend/src/main/resources/db/migration` ([ADR-0014](docs/adr/0014-database-migrations-flyway.md)) are the source of truth for the schema; if the diagram and the migrations disagree, the migrations are right. Update this diagram in the same pull request that changes the schema.

## 📚 Documentation

- [Development guide](docs/development.md)
- [Deployment guide](docs/deployment.md) (local Compose stack for now)
- [Database guide](docs/database.md) and [database schema](docs/database.drawio.svg)
- [Datasets and measurements](docs/datasets.md) and [demo script](docs/demo.md)
- [Testing guide](docs/testing.md)
- [API guide](docs/api.md) and [OpenAPI contract](docs/api/openapi.yml)
- [Security guide](docs/security.md)
- [C4 architecture model](docs/architecture/)
- [CI guide](docs/ci.md)
- [Architecture Decision Records](docs/adr/)
- [Changelog](CHANGELOG.md)
- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [AI Agents Context](AGENTS.md)

Architecture documents distinguish accepted decisions from proposals. Check each ADR status before treating it as implemented.

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
