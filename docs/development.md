# Development Guide

This guide explains how to run the UrbanPulse source code locally and start working on the backend.

## Prerequisites

Install:

- Java 25
- Docker Engine or Docker Desktop
- Docker Compose v2
- Git
- IntelliJ IDEA (optional)

Maven does not need to be installed globally. The project includes Maven Wrapper.

Verify the required tools:

```bash
java --version
docker --version
docker compose version
```

## How to run the Project with IntelliJ IDEA (recommended)

Clone the repository and enter the backend directory:

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/urbanpulse
```

Open IntelliJ IDEA and:

1. Select **Open** and choose the `urbanpulse/` directory.
2. Import the project as a Maven project if IntelliJ does not detect it automatically.
3. Select Java 25 as the project SDK.
4. Make sure Docker process is running.
5. Run `UrbanpulseApplication` from `src/main/java/es/uma/urbanpulse/UrbanpulseApplication.java`.

IntelliJ is the recommended option because it provides integrated execution, debugging, Maven
support, and code navigation. Spring Boot detects `docker-compose.yaml` and starts the PostgreSQL
container automatically.

This is also the IDE we are using to develop this application.

Stop the application using IntelliJ's stop button. Spring Boot will also stop the database container it started automatically.

## Run from the Terminal

On Linux/MacOS:

```bash
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run
```

Spring Boot detects `docker-compose.yaml` and starts the PostgreSQL container automatically. The backend
runs directly on the host.

Stop the application with `Ctrl+C`. Spring Boot will also stop the database container it started.

## Project Structure

```text
urbanpulse/
|-- docker-compose.yaml
|-- pom.xml
|-- mvnw
|-- mvnw.cmd
`-- src/
    |-- main/java/es/uma/urbanpulse/
    |-- main/resources/application.yaml
    `-- test/java/es/uma/urbanpulse/
```

Application code - `src/main/java/es/uma/urbanpulse/`.
Configuration - `src/main/resources/`.

## Run Tests

From the `urbanpulse/` directory:

On Linux/MacOS:

```bash
./mvnw verify
```

On Windows PowerShell:

```powershell
./mvnw.cmd verify
```

Docker must be running because the application context uses the database declared in
`docker-compose.yaml`.
