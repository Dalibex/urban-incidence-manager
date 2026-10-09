# Urban Pulse - Urban Incidence Manager

Cloud platform for intelligent management of urban incidents reported by citizens.

## Main Features

Currently the repository contains the initial backend skeleton and local development
infrastructure, this is not even an alpha version.

## What's For?

UrbanPulse combines citizen reports with contextual urban data from the city of Malaga to
improve the classification, prioritization, tracking, analysis, and resolution of urban
incidents.

## Technologies

- Spring Boot 4.1.1 (Java 25) with Spring Data JPA
- PostgreSQL with PostGIS, run locally through Docker Compose
- Maven Wrapper (no global Maven installation required)

## Installation

Requirements: Java 25, Docker Engine or Docker Desktop, Docker Compose v2, Git.

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/urbanpulse
cp .env.example .env
```

See [docs/development.md](docs/development.md) for the full setup. **(TBC)**

## Configuration

Local database settings live in `urbanpulse/.env` (not versioned). Spring Boot detects
`docker-compose.yaml` and connects to the database automatically. See
[docs/deployment.md](docs/deployment.md). **(TBC)**

## Testing

```bash
cd urbanpulse
./mvnw.cmd verify
```

Docker must be running, because the application context uses the database declared in
`docker-compose.yaml`.

## Deployment

The PostgreSQL/PostGIS database is deployed with Docker Compose. Containerizing the backend and
other deployment decisions are tracked in the architecture decisions under
[docs/adr](docs/adr/).