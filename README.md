# 🌆 UrbanPulse

[![CI](https://github.com/Dalibex/urban-incidence-manager/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Dalibex/urban-incidence-manager/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Dalibex/urban-incidence-manager?include_prereleases&sort=semver)](https://github.com/Dalibex/urban-incidence-manager/releases)
[![License](https://img.shields.io/github/license/Dalibex/urban-incidence-manager)](LICENSE)

UrbanPulse manages the urban incidents of Málaga, from the citizen's report to its resolution: citizens report them from a mobile app, and the city council's staff classify, assign, resolve and analyse them from a web app. It assists the staff; it never takes a sensitive decision for them.

> [!NOTE]
> **In development.** What is built is in [Current Scope](#-current-scope), the status of each requirement in the [requirements](docs/requirements.md), and how to use what is built in the [user guide](docs/user-guide.md).

## 📑 Contents

- **[Current Scope](#-current-scope)**: what each role can do today.
- **[How It Works](#-how-it-works)**: the life of an incident, from the report to its closure.
- **[Technology Stack](#-technology-stack)**: each part, what it is built with and the decision behind it.
- **[Quick Start](#-quick-start)**: the whole system running on your machine, with the demo users.
- **[Testing](#-testing)**: how to run the tests.
- **[Project Structure](#-project-structure)**: what each folder holds.
- **[Documentation](#-documentation)**: every document of the project, by what you want to do.
- **[License](#-license)**: Apache 2.0.

## 📦 Current Scope

What each role can do today:

| Who | App | What they can do |
| --- | --- | --- |
| Citizen | Mobile | Sign up, report an incident by placing it on the map, add photos, and follow their reports and what the city answers |
| Municipal operator | Web | Review new reports in an inbox with filters or on a live map with a dashboard; classify, validate, reject or ask for more information; assign to a department and a technician; close or reopen the result |
| Technician | Web | Accept the incidents assigned to them and resolve them with their notes |
| Administrator | Web | Everything an operator does, plus managing the staff accounts and reading the audit log |
| Analyst | Web | Operational reports for a period, on screen or as CSV |

Every incident follows a fixed lifecycle ([ADR-0020](docs/adr/0020-incident-lifecycle-transitions.md)). Each change keeps who made it, when and why, and the citizen never sees the internal notes.

Also built: the deployment of the whole system behind nginx, sample data for development, the demo and the measurements ([datasets](docs/datasets.md)), the base map, and the CI pipeline. What is not built yet says `Planned: #N` in the [requirements](docs/requirements.md).

## 🔄 How It Works

The life of one incident, from the citizen's phone to its closure:

1. 📱 **A citizen reports it** from the mobile app: a title, a description, an optional category and
   a pin on the map of Málaga. Then they can add photos.
2. 🌐 **nginx receives the request**, the only entry point of the system, and passes it to the API.
3. ☕ **The API checks who is asking and what they may do,** saves the incident in PostgreSQL and
   its photos in a volume, and records the change in its history.
4. 💻 **An operator reviews it** on the web app, in the inbox or on the live map: classifies it,
   validates it (or rejects it, or asks the citizen for more information) and assigns it to a
   department and a technician.
5. 🔧 **The technician accepts the work and resolves it,** and the operator closes it, or reopens it
   if it is not solved.
6. 👀 **At every step the citizen sees the new status** in the app, but never the internal notes of
   the staff or who made each change.

Every change of status follows the lifecycle of
[ADR-0020](docs/adr/0020-incident-lifecycle-transitions.md), and two people can never overwrite each
other's change: the second one is asked to reload. Analysts read reports built from the same data,
and administrators manage the staff accounts and read the audit log.

> [!TIP]
> How each role does this, screen by screen, is in the [user guide](docs/user-guide.md). Why the
> system is built this way, its quality goals and its accepted risks are in the
> [architecture overview](docs/architecture.md) and the [C4 model](docs/architecture/).

## 🧰 Technology Stack

| Part | Built with | Decided in |
| --- | --- | --- |
| Mobile app, for citizens | TypeScript, React Native, Expo (development build), MapLibre React Native | [ADR-0005](docs/adr/0005-mobile-application-technology.md) |
| Web app, for staff | TypeScript, React, Vite, TanStack Query, MapLibre | [ADR-0006](docs/adr/0006-frontend-web-technology.md) |
| Shared client packages | A pnpm workspace on Node.js 24, with an API client generated from the contract | [ADR-0007](docs/adr/0007-client-application-architecture.md) |
| Backend API | Java 25 and Spring Boot 4.1 (Web, Security, Data JPA): one deployable organised in modules by feature, with a REST API designed contract first in [OpenAPI 3.1](docs/api/openapi.yml) | [ADR-0008](docs/adr/0008-backend.md), [ADR-0010](docs/adr/0010-modular-monolith.md) |
| Database | PostgreSQL 18 with PostGIS 3.6, changed only by Flyway migrations | [ADR-0001](docs/adr/0001-database.md), [ADR-0003](docs/adr/0003-geospatial-data-management.md), [ADR-0014](docs/adr/0014-database-migrations-flyway.md) |
| Base map | Our own vector map of Málaga, built from OpenStreetMap: no tile provider and no API key | [ADR-0016](docs/adr/0016-self-hosted-osm-base-map.md) |
| Deployment | Docker Compose on one machine, behind nginx, the only published port | [ADR-0011](docs/adr/0011-deployment-12factor.md), [ADR-0017](docs/adr/0017-reverse-proxy.md) |
| Quality | GitHub Actions, Testcontainers, Spotless, JaCoCo and SpotBugs | [ADR-0015](docs/adr/0015-ci-pipeline-and-pre-commit-formatting.md) |

## ⚡ Quick Start

The whole system in Docker, behind nginx, with the demo users. You only need Docker and Git: the images compile the backend and the web app, so you need no Java and no Node. From a terminal:

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager
cp .env.example .env
```

In `.env` (on Windows, copy it with `Copy-Item .env.example .env`), fill in, without quotes:

- `POSTGRES_PASSWORD`, any password for the database;
- `JWT_SECRET`, at least 32 bytes: generate one with `openssl rand -base64 48` (on Windows, from Git Bash);
- `SPRING_PROFILES_ACTIVE=demo` and a `DEMO_PASSWORD`, the password of the demo users.

> [!IMPORTANT]
> The `demo` profile loads sample users and incidents for trying the system. A real deployment
> starts with `SPRING_PROFILES_ACTIVE` empty and creates its first administrator instead
> ([deployment guide](docs/deployment.md#-deploy-the-whole-system)).

Then start the system and build the base map of Málaga. The first time each takes a few minutes:

```bash
docker compose up -d --build
docker compose --profile tiles run --rm tiles-builder
```

Open `http://localhost:8000` and sign in as `demo.operator@example.com` with your `DEMO_PASSWORD`. The other demo users, and how to reach the system from a phone, are in the [demo script](docs/demo.md). `docker compose down` stops it and keeps the data.

## 🧪 Testing

- **Backend:** `./mvnw verify` from `backend/` (`./mvnw.cmd verify` on Windows), with Java 25 and Docker running. It runs the unit and integration tests, the formatting check, the coverage gate and SpotBugs.
- **Clients:** `pnpm test` from `frontend/`.

The kinds of tests and how to run part of them are in the [testing guide](docs/testing.md); what each CI job checks, in the [CI guide](docs/ci.md).

## 📁 Project Structure

| Folder | What it holds |
| --- | --- |
| `backend/` | The Spring Boot API, its Dockerfile and the Compose file of development ([modules](docs/development.md#-backend-structure)) |
| `frontend/` | The pnpm workspace: `apps/web`, `apps/mobile` and the shared `packages/` ([layout](docs/development.md#-client-workspace)), and the web image with nginx |
| `docs/` | The guides below, the [ADRs](docs/adr/), the [OpenAPI contract](docs/api/openapi.yml) and the [C4 model](docs/architecture/) |
| `tiles/` | The builder of the base map ([ADR-0016](docs/adr/0016-self-hosted-osm-base-map.md)) |
| `.github/`, `.githooks/` | CI workflows, issue and pull request templates, and the pre-commit hook |
| `docker-compose.yaml`, `.env.example` | The deployment: the whole system behind nginx |

## 📚 Documentation

Every document of the project, grouped by what you want to do.

### 🚀 Use it

- **[Quick Start](#-quick-start)**: try the whole system on your machine, with Docker only.
- **[Demo script](docs/demo.md)**: give the demo on a test system with sample data.
- **[User guide](docs/user-guide.md)**: use the apps as a citizen, operator, technician, administrator or analyst.

### 🛠 Build and run it

- **[Development guide](docs/development.md)**: run and develop the backend and the apps, and fix what usually goes wrong.
- **[Testing guide](docs/testing.md)**: run the tests and know what each kind checks.
- **[CI guide](docs/ci.md)**: know what the CI checks, and fix a red job.
- **[Deployment guide](docs/deployment.md)**: deploy on a server, configure it, back it up and update it.

### 🧠 Understand it

- **[Architecture overview](docs/architecture.md)**: why it is built this way, its quality scenarios and its accepted risks.
- **[C4 model](docs/architecture/)**: the structure, from the context to the deployment.
- **[ADRs](docs/adr/)**: each decision and its reasons; a *Proposed* one is not decided yet.
- **[Requirements](docs/requirements.md)**: what is built and what is planned, with its priority.
- **[API guide](docs/api.md)** and **[OpenAPI contract](docs/api/openapi.yml)**: use the API, or add and change an endpoint.
- **[Security guide](docs/security.md)**: who may do what, with tokens, sessions, limits and permissions.
- **[Database guide](docs/database.md)**: change the schema, an index or the sample data.
- **[Datasets and measurements](docs/datasets.md)**: load the demo or performance data, and read the measurements.
- **[Twelve-factor traceability](docs/twelve-factor.md)**: how each Twelve-Factor principle is applied.

### 🤝 Contribute

- **[Contributing guide](CONTRIBUTING.md)**: the workflow, from the issue to the merge.
- **[Code of Conduct](CODE_OF_CONDUCT.md)**: how everyone who takes part is expected to behave.
- **[Changelog](CHANGELOG.md)**: what changed in each version.
- **[Security policy](SECURITY.md)**: how to report a vulnerability privately.
- **[AGENTS.md](AGENTS.md)**: the rules that AI agents follow here.

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
