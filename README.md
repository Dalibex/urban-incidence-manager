# UrbanPulse

UrbanPulse is a platform for managing urban incidents reported by citizens and enriching them with contextual data from the city of Malaga.

> **Status:** Early development. The repository currently contains the Spring Boot backend skeleton and its local PostgreSQL/PostGIS infrastructure. Incident management workflows, web and mobile clients are not implemented yet.

## Purpose

UrbanPulse aims to support the registration, classification, prioritization, tracking, analysis, and resolution of urban incidents.

The platform assists municipal staff. It does not replace human decisions for sensitive actions.

## Current Scope

The repository currently provides:

- A Spring Boot backend built with Java 25.
- PostgreSQL with PostGIS for relational and geospatial data.
- Spring Data JPA and PostgreSQL connectivity.
- Docker Compose integration for local database startup.
- Maven Wrapper.
- Basic application-context testing.

## Technology Stack

- Java 25
- Spring Boot 4.1.1
- Spring Data JPA
- Spring Modulith
- PostgreSQL 18 with PostGIS 3.6
- Docker Compose
- Maven Wrapper

## Prerequisites

Install Java 25, Docker Engine or Docker Desktop, and Git. Maven and PostgreSQL do not need to be installed locally.

## Quick Start

Clone the repository and enter the backend directory:

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/urbanpulse
```

### Create the local environment file

Linux or macOS:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Replace `POSTGRES_PASSWORD` in `.env`, then start the backend.

### Run the application

Linux or macOS:

```bash
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

Spring Boot detects `docker-compose.yaml`, starts PostgreSQL/PostGIS, waits until the database is healthy, and configures the connection.

## Testing

Docker must be running before executing the application-context tests.

Linux or macOS:

```bash
./mvnw verify
```

Windows PowerShell:

```powershell
./mvnw.cmd verify
```

## Project Structure

```text
urban-incidence-manager/
|-- docs/
|   |-- adr/
|   |-- deployment.md
|   `-- development.md
|-- urbanpulse/
|   |-- docker-compose.yaml
|   |-- pom.xml
|   |-- mvnw
|   |-- mvnw.cmd
|   `-- src/
|-- AGENTS.md
|-- CHANGELOG.md
`-- README.md
```

## Documentation

- [Development guide](docs/development.md)
- [Deployment guide](docs/deployment.md)
- [Architecture Decision Records](docs/adr/)
- [Changelog](CHANGELOG.md)
- [AI Agents Context](AGENTS.md)

Architecture documents distinguish accepted decisions from proposals. Check each ADR status before treating it as implemented.

## License

This project is licensed under the [Apache License 2.0](LICENSE).
