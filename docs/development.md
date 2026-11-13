# 🛠️ Development Guide

This guide explains how to run the UrbanPulse backend and clients on your machine and start working
on them. Other guides cover the rest:

| Guide | What it covers |
| --- | --- |
| [Architecture](architecture.md) | The architecture drivers, a summary of the containers and the quality attribute scenarios |
| [Deployment](deployment.md) | The deployment Compose behind nginx, the development Compose services, their variables and volumes, the backend in Docker, backups, resetting the database, the base map |
| [Database](database.md) | Migrations, reference data and how the data profiles work |
| [Datasets and measurements](datasets.md) | The demo and performance datasets, and the quality attribute measurements |
| [Testing](testing.md) | Backend and client tests |
| [CI](ci.md) | The CI jobs, the pre-commit hook and the checks to run before pushing |
| [API](api.md) | The OpenAPI contract and how to add an endpoint |
| [Security](security.md) | Tokens, sessions, the audit trail and the permission matrix |

## 🐛 Prerequisites

Install:

- Java 25
- Docker Engine or Docker Desktop
- Docker Compose v2
- Git
- Node.js 24 and pnpm, for the web and mobile clients (see *Client Workspace*)
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

Replace `POSTGRES_PASSWORD` in `.env` and fill in `JWT_SECRET`, the secret that signs the access
tokens ([security guide](security.md)). The backend does not start without it. Generate one with
(in Windows, from Git Bash):

```bash
openssl rand -base64 48
```

Never commit `.env`; only `.env.example` is versioned. The backend reads `backend/.env` by itself
when it runs from `backend/` (IDE or `./mvnw spring-boot:run`), and Docker Compose passes it to the
container, so no other setup is needed. The backend reads `.env` as a properties file, so write
the values without quotes (`JWT_SECRET=abc...`, not `JWT_SECRET="abc..."`): quotes would become part
of the secret.

Every variable is described in the [deployment guide](deployment.md). Change `POSTGRES_PORT` (5432)
or `BACKEND_PORT` (8080) only if something else on your computer already uses that port.

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

From the IDE or the terminal, the backend listens on port 8080 of every interface, so a phone in
your local network can reach it. To run it as a container instead, with the same image as the CI,
see *Run the Backend in Docker* in the [deployment guide](deployment.md).

Run the tests with `./mvnw verify` ([testing guide](testing.md)). Java code is formatted on commit
by the pre-commit hook; `./mvnw spotless:apply` formats it by hand ([CI guide](ci.md)).

## 🧪 Development Dataset

The `dev` Spring profile loads a shared development dataset (#83): a user of every role, incidents
in every status of the lifecycle with their history, and the assignments, comments, attachment
metadata, urban context, external observations, urban assets and notifications around them. Use it
to try the API and the clients without creating data by hand. Never activate it outside a developer
machine: production starts with no profile and no sample data.

`application-dev.yaml` adds `classpath:db/dev-data` to the Flyway locations, and
`db/dev-data/R__dev_data.sql` is a repeatable migration. When it reloads, why it cannot be combined
with another profile and how to change it are explained in *Data Profiles* in the
[database guide](database.md). The demo and performance datasets are in the
[datasets guide](datasets.md).

### Activate it

- IntelliJ IDEA: in the run configuration of `UrbanpulseApplication`, set **Active profiles** to
  `dev`.
- Terminal, from `backend/`: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (Windows
  PowerShell: `./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`).
- Docker: set `SPRING_PROFILES_ACTIVE=dev` in `.env` and run `docker compose --profile app up
  --build`. The variable only reaches the container: the IDE and `./mvnw` read `.env` as a
  properties file and do not activate a profile from it, so use the options above there.

Every user signs in with the password `urbanpulse-dev`:

| Email | Role |
| --- | --- |
| `dev.citizen@example.com` | `CITIZEN` |
| `dev.citizen2@example.com` | `CITIZEN` |
| `dev.operator@example.com` | `MUNICIPAL_OPERATOR` |
| `dev.technician@example.com` | `TECHNICIAN` |
| `dev.technician2@example.com` | `TECHNICIAN` |
| `dev.admin@example.com` | `ADMINISTRATOR` |
| `dev.analyst@example.com` | `ANALYST` |
| `dev.newstaff@example.com` | `MUNICIPAL_OPERATOR`, must change the password at its first sign-in (#165) |

`dev.newstaff` behaves like an account an administrator has just created: the web asks for a new
password before it signs in. Once changed, it keeps the new one until `R__dev_data.sql` changes or
the database is reset.

When `R__dev_data.sql` changes, its users and urban assets are updated in place by their fixed ids;
incidents are deleted and inserted again (see *When the data reloads* in the
[database guide](database.md)).

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
`@urbanpulse/api-client`), as source: there is no separate build step for the packages. The
`api-client` types are generated from the contract; see *Generated client* in the
[API guide](api.md).

### Setup

Use the Node version in `.nvmrc` (24) and the pnpm version in `packageManager`. Corepack, which
ships with Node, installs that exact pnpm version:

```bash
corepack enable
```

On Windows it writes to `C:\Program Files\nodejs`, so run it once from a terminal opened as
administrator; otherwise it fails with `EPERM`. Skip it if `pnpm --version` already prints the
version in `packageManager`.

Then, from `frontend/`:

```bash
cd frontend
```

```bash
pnpm install
```

Always use `pnpm`, never `npm install` or `yarn`: only `pnpm-lock.yaml` is versioned.

### Run the web app

From `frontend/`:

```bash
pnpm dev:web
```

The web app opens at `http://localhost:5173`. It calls the API on its own origin (`/api/v1`) and
Vite forwards `/api` to the backend at `http://localhost:8080`, so the browser needs no CORS. To
use another backend, set `API_PROXY_TARGET` before `pnpm dev:web`. The web keeps the session in
memory only: reloading the page asks for sign-in again. To sign in as each demo user with one
click, see *Quick Sign-In* in the [demo script](demo.md); it is off by default.

In the deployment, nginx plays the part of Vite: it serves the production build, the base map at
`/tiles/` and `/api` on the same origin, so the code and the bundle are the same
([deployment guide](deployment.md)). To try that build behind nginx, use the deployment Compose at
the repository root; the development stack never runs nginx.

The web reads the API through React Query ([ADR-0006](adr/0006-frontend-web-technology.md)): each
screen calls a hook such as `useIncidentPage` (`apps/web/src/incidents/incident-queries.ts`), which
caches the result and shares it between screens. Only failures that may pass on their own are
retried: the API not reachable or a `5xx`; a `4xx` is shown at once. Signing out, or a session
that expires, empties the cache, so the next user never sees the data of the previous one.

### Run the mobile app

The mobile app runs as a **development build**: an UrbanPulse app of its own, compiled on your
machine and installed on the phone. **Expo Go does not work**, because the base map uses MapLibre
React Native, a native module that Expo Go does not include
([ADR-0016](adr/0016-self-hosted-osm-base-map.md)). These steps cover Android; an iPhone needs a
Mac with Xcode, and installing on a physical iPhone needs a paid Apple developer account.

#### Install the Android tools, once

1. Install [Android Studio](https://developer.android.com/studio). In its setup wizard keep
   *Android SDK*, *Android SDK Platform-Tools* and *Android SDK Build-Tools*.
2. Point the build at the SDK and at the JDK that comes with Android Studio, so it does not depend
   on the Java installed for the backend. On Windows PowerShell, for the current terminal:

   ```powershell
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:Path = "$env:ANDROID_HOME\platform-tools;$env:JAVA_HOME\bin;$env:Path"
   ```

   On Linux or macOS, `export ANDROID_HOME=$HOME/Android/Sdk` (`$HOME/Library/Android/sdk` on
   macOS) and add `$ANDROID_HOME/platform-tools` to `PATH`.
3. On the phone, enable *Developer options* (tap *Build number* seven times in *About phone*) and
   turn on *USB debugging*. Connect it by USB, accept the prompt on the phone and check that it is
   listed:

   ```bash
   adb devices
   ```

#### Configure the app

The app reads the API and the map style from `apps/mobile/.env`, which is not versioned. Copy the
example and set the address of your machine in both variables: on a phone, its address in the local
network (find it with `ipconfig` on Windows or `ip addr` on Linux); on the Android emulator,
`10.0.2.2`, which also reaches a backend running in Docker:

```bash
cp apps/mobile/.env.example apps/mobile/.env
```

The map loads from the web development server. Build the base map with that address, as *Build the
Base Map* in the [deployment guide](deployment.md) explains (`TILES_BASE_URL=http://<address>:5173/tiles`),
and start the web app so the phone can reach it:

```bash
pnpm dev:web --host
```

Without `EXPO_PUBLIC_MAP_STYLE_URL`, or with the web app stopped, the report form shows a notice
instead of the map. The citizen can still report with the GPS position, but without it the form
waits for the map: *Submit* stays disabled until the location is allowed, or until the map loads
and the citizen moves the pin to the incident.

#### Build, install and run

**On Windows**, the native build writes paths longer than 260 characters inside
`node_modules\.pnpm`, and two tools stop there even with long paths enabled in Windows:

- `cmd.exe` cannot start a batch file with a longer path: the build fails with
  `CreateProcess error=2` on a `prefab_command.bat`. Keep the checkout in a short folder: one of
  about 40 characters, such as `C:\GitHub\urban-incidence-manager`, works; one of 70 under
  `C:\Users\<user>\Desktop\` is too deep. A `subst` drive does not help, because the build
  resolves the real path again.
- The `ninja` of the default CMake (3.22.1, ninja 1.10) fails with `Filename longer than 260
  characters`. In Android Studio, *SDK Manager → SDK Tools*, tick *Show Package Details* and
  install CMake 4.1.2 (ninja 1.12), then point the build at it in
  `apps/mobile/android/local.properties`, which Git ignores. Create the file if it is missing (a
  failed build does not always write it), and again whenever `apps/mobile/android/` is deleted:

  ```properties
  cmake.dir=C\:/Users/<user>/AppData/Local/Android/Sdk/cmake/4.1.2
  ```

With the phone connected, from `frontend/`:

```bash
pnpm --filter @urbanpulse/mobile android
```

The first build takes several minutes: it generates `apps/mobile/android/` (ignored by Git), compiles
the native code, installs the app on the phone and starts Metro. Later, while no native dependency
or `app.json` changes, there is no need to build again: start Metro and open the UrbanPulse app on
the phone.

```bash
pnpm dev:mobile
```

The phone loads the JavaScript from Metro, the API and the map from your machine, all over the
local network. If the phone cannot reach your machine, the app stops on an error screen of the
Expo launcher. Some routers isolate the devices of their Wi-Fi from each other: then connect the
phone to your machine another way, for example sharing the connection over the USB cable. If
Windows asks whether Node.js or Java may use the network, allow private networks. After adding a
native dependency or changing `app.json`, build again with `pnpm --filter @urbanpulse/mobile android`.

- The mobile app only targets Android and iOS (`platforms` in `app.json`); the browser client is
  `apps/web`.
- A phone or emulator cannot reach the backend through `localhost`: the Android emulator reaches
  your machine at `10.0.2.2`, a phone at its address in the local network. A phone cannot reach the
  backend running in Docker, only one started from the IDE or the terminal (see *Reach the API
  from a Phone* in the [deployment guide](deployment.md)).
- The mobile app keeps the session in `expo-secure-store` (Keychain on iOS, Keystore on Android),
  so it survives a restart.
- The report form places the incident with a pin fixed in the centre of the base map: it starts on
  the GPS position or, without it, on Málaga, and the citizen moves the map under the pin. On an
  emulator, set the position in its extended controls (*Location*) to try the GPS path.

### Before pushing

Run the checks of the `frontend` CI job and format the code as described in
[8.1 Before pushing](ci.md#81-before-pushing) in the CI guide. The client tests are in the [testing guide](testing.md).

## 🔙 Backend Structure

```text
backend/
|-- .env.example
|-- docker-compose.yaml
|-- Dockerfile
|-- pom.xml
|-- mvnw
|-- mvnw.cmd
`-- src/
    |-- main/java/es/uma/urbanpulse/
    |   |-- UrbanpulseApplication.java
    |   |-- identity/        users, roles, sign-up and sessions, access and refresh tokens, audit trail
    |   |-- incidents/       incidents, location, categories, lifecycle, assignments, comments, attachments
    |   |-- urbancontext/    external context of an incident (no provider integrated yet)
    |   |-- geospatial/      districts, neighbourhoods, urban assets (PostGIS)
    |   |-- notifications/   notifications (no delivery provider integrated yet)
    |   |-- analytics/       read-only reports over the incidents (operational report)
    |   `-- shared/          cross-cutting code, not a module
    |       |-- security/    Spring Security filter chain, JWT validation, AuthenticatedUser
    |       `-- web/         error responses (Problem Details), paging
    |-- main/resources/
    |   |-- application.yaml, application-<profile>.yaml
    |   `-- db/              migration/ and the folder of each data profile
    `-- test/java/es/uma/urbanpulse/
```

Each module uses the folders it needs from the same plain MVC layout: `controller/`, `dto/`,
`entity/`, `mapper/`, `repository/`, `service/`, plus `client/` when it calls an external system.

Modules are packages for organisation only ([ADR-0010](adr/0010-modular-monolith.md)). Any class may
use another module's services or entities.

Every package has a `package-info.java` that says what it does and which tables it owns.

The external providers proposed for weather and traffic (Open-Meteo and TomTom,
[ADR-0018](adr/0018-weather-and-traffic-providers.md)) and for notification delivery (Brevo and
Twilio, [ADR-0019](adr/0019-notification-delivery-providers.md)) are not decided yet: both ADRs are
*Proposed*.
