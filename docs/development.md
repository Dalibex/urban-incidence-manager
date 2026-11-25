# 💻 Development Guide

How to run the UrbanPulse backend and clients on your machine, work on them, and fix what usually
goes wrong.

> [!TIP]
> To try the whole system without Java or Node, use *Quick Start* in the
> [README](../README.md#-quick-start) instead. Every other guide is listed in *Documentation* in the
> README.

## 📑 Contents

- **[Prerequisites](#-prerequisites)**: what to install.
- **[Get the Code](#-get-the-code)**: clone the repository.
- **[Create the Local Environment File](#-create-the-local-environment-file)**: `backend/.env`, and which `.env` each way of running reads.
- **[Run with IntelliJ IDEA (recommended)](#-run-with-intellij-idea-recommended)**: start the backend from the IDE.
- **[Run from the Terminal](#-run-from-the-terminal)**: start the backend with Maven.
- **[Development Dataset](#-development-dataset)**: sample users and incidents for development.
- **[Client Workspace](#-client-workspace)**: the web and mobile apps: set up, run and check them.
- **[Backend Structure](#-backend-structure)**: the modules and the layout of each one.
- **[Troubleshooting](#-troubleshooting)**: common problems and their fixes.

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

Clone the repository and enter the backend directory. The commands of this guide say the folder they
run from, relative to the repository root.

```bash
git clone https://github.com/Dalibex/urban-incidence-manager.git
cd urban-incidence-manager/backend
```

## 📝 Create the Local Environment File

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
tokens ([security guide](security.md)), and `MFA_ENCRYPTION_KEY`, the key of the second factor of
staff (`openssl rand -base64 32`). The backend does not start without them. Set
`MFA_REQUIRED_ROLES=` (empty) if you want to sign staff in locally without a code. Generate the
secret with (in Windows, from Git Bash):

```bash
openssl rand -base64 48
```

> [!WARNING]
> Never commit `.env`; only `.env.example` is versioned. Write the values without quotes
> (`JWT_SECRET=abc...`, not `JWT_SECRET="abc..."`): the backend reads `.env` as a properties file,
> so the quotes would become part of the secret.

The backend reads `backend/.env` by itself
when it runs from `backend/` (IDE or `./mvnw spring-boot:run`), and Docker Compose passes it to the
container, so no other setup is needed.

The photos of the incidents go to `STORAGE_BASE_PATH`, `./storage` in the example: the folder
`backend/storage/`, which Git ignores. A relative path is resolved
from the folder the backend runs in, so keep `backend/` as the working directory of the run
configuration, as `.env` already needs. The container of `--profile app` ignores this value and keeps
the photos in its `evidence_data` volume, at `/app/storage`.

> [!NOTE]
> A `backend/.env` copied from an older example has `STORAGE_BASE_PATH=/app/storage`, the folder inside the
> container. From the host that is `C:\app\storage` on Windows, and on macOS or Linux the upload fails
> with `500` because a normal user cannot write to `/app`. Change it to `./storage`.

Every variable is described in the [deployment guide](deployment.md). If something else on your
computer already uses a port, change `POSTGRES_PORT` (5432) for the database. `BACKEND_PORT` (8080)
only moves the backend container (`--profile app`); from the IDE or `./mvnw`, set `SERVER_PORT` in the
environment of the run configuration instead, and then `API_PROXY_TARGET` for `pnpm dev:web` and the
port in `apps/mobile/.env`.

### Which `.env` each way of running reads

The repository has four `.env` files, one per thing you start. None is versioned: each one is a copy
of the `.env.example` next to it.

| File | Read by | What you set in it |
| --- | --- | --- |
| `backend/.env` | The backend from the IDE or `./mvnw`, and the development Compose (`backend/docker-compose.yaml`) | Database, `JWT_SECRET`, `DEMO_PASSWORD`, and `TILES_BASE_URL` for the base map of development |
| `frontend/apps/web/.env` | `pnpm dev:web` (Vite) only; the web image of the deployment ignores it | The quick sign-in of the demo, and `VITE_MAP_STYLE_URL` if the map is not on the web's own origin |
| `frontend/apps/mobile/.env` | `pnpm dev:mobile` (Metro) | Where the app finds the API and the map, and the quick sign-in of the demo |
| `.env` at the repository root | The deployment Compose (`docker-compose.yaml` at the root) | Everything the deployment needs ([deployment guide](deployment.md#-deploy-the-whole-system)) |

The mobile app reaches a different port in each case. Use your machine's address in the local
network, never `localhost`:

| The system runs… | `EXPO_PUBLIC_API_BASE_URL` | `EXPO_PUBLIC_MAP_STYLE_URL` |
| --- | --- | --- |
| In development: the backend from the IDE or `./mvnw`, and `pnpm dev:web --host` | `http://<address>:8080/api/v1` | `http://<address>:5173/tiles/style.json` |
| In the deployment Compose, behind nginx | `http://<address>:8000/api/v1` | `http://<address>:8000/tiles/style.json` |

After changing `apps/mobile/.env`, start Metro with `pnpm dev:mobile --clear`, so the app reads the
new values.

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
to try the API and the clients without creating data by hand.

Its urban contexts cover every status of the schema, but the `PENDING` one belongs to an incident
reported weeks ago: the first run of the weather worker, 30 seconds after start-up, marks it
`UNAVAILABLE` because it is past `WEATHER_MAX_DELAY` (ADR-0018), and it stays so until the dataset
reloads. To keep it `PENDING`, for example to try how the web shows it, start the backend with
`WEATHER_ENRICHMENT_ENABLED=false`.

> [!CAUTION]
> The `dev` and `perf` datasets publish their passwords, administrators included. Never activate them
> on a machine other people can reach: production starts with no profile and no sample data.

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

Skip it if `pnpm --version` already prints the version in `packageManager`. On Windows it fails with
`EPERM` unless the terminal runs as administrator (see [Troubleshooting](#-troubleshooting)).

Then go to `frontend/`, from the repository root:

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

> [!TIP]
> **On Windows**, read *The Android build fails on Windows* in [Troubleshooting](#-troubleshooting)
> before the first build: the checkout must be in a short folder, and the build needs a newer CMake.

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
local network. If it cannot reach your machine, see *The phone cannot reach your machine* in
[Troubleshooting](#-troubleshooting). After adding a native dependency or changing `app.json`,
build again with `pnpm --filter @urbanpulse/mobile android`.

- The mobile app only targets Android and iOS (`platforms` in `app.json`); the browser client is
  `apps/web`.
- A phone or emulator cannot reach the backend through `localhost`: the Android emulator reaches
  your machine at `10.0.2.2`, a phone at its address in the local network. A phone cannot reach the
  backend of the development Compose, which only listens on `127.0.0.1`; it reaches one started
  from the IDE or the terminal, or the deployment Compose through nginx (see *Which `.env` each way
  of running reads* above, and *Reach the API from a Phone* in the [deployment guide](deployment.md)).
- The mobile app keeps the session in `expo-secure-store` (Keychain on iOS, Keystore on Android),
  so it survives a restart.
- The report form places the incident with a pin fixed in the centre of the base map: it starts on
  the GPS position or, without it, on Málaga, and the citizen moves the map under the pin, or
  searches an address above it (`GET /places`, which the backend answers from Nominatim). On an
  emulator, set the position in its extended controls (*Location*) to try the GPS path. The search
  reaches the real Nominatim, one call a second for the whole backend: a burst of searches answers
  `429`.

### Before pushing

Run the checks of the `frontend` CI job and format the code as described in
[Before pushing](ci.md#before-pushing) in the CI guide. The client tests are in the [testing guide](testing.md).

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
    |   |-- urbancontext/    external context of an incident: its weather from Open-Meteo, after the report
    |   |-- geospatial/      districts, neighbourhoods, urban assets (PostGIS); the address of an incident from Nominatim
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

The weather of each new incident comes from Open-Meteo ([ADR-0018](adr/0018-weather-and-traffic-providers.md)):
the report only inserts its `PENDING` `urban_context`, and a scheduled worker of `urbancontext`
fills it every 30 seconds. The backend run from the IDE calls Open-Meteo too, so it needs
internet; without it, set `WEATHER_ENRICHMENT_ENABLED=false` in `backend/.env` and the weather
stays pending. The tests never call it. The address of each new incident works the same way with
Nominatim ([ADR-0002](adr/0002-open-street-map.md)): the report saves its location
with the address `PENDING`, and a worker of `geospatial` resolves it every 30 seconds, one call per
second at most. Without internet, set `ADDRESS_ENRICHMENT_ENABLED=false`: the address stays
pending, and the address search of the mobile app answers `503`. The provider proposed for traffic (TomTom,
[ADR-0024](adr/0024-traffic-provider.md)) is not decided yet: the ADR is *Proposed*. Notification
delivery is decided (Brevo for email and the Expo push service, no SMS:
[ADR-0031](adr/0031-push-and-email-notifications.md), which supersedes
[ADR-0019](adr/0019-notification-delivery-providers.md)) but not built yet: #373.

## 🧯 Troubleshooting

### `corepack enable` fails with `EPERM` on Windows

It writes to `C:\Program Files\nodejs`. Run it once from a terminal opened as administrator.

### Maven, the pre-commit hook or GitHub Desktop complain about Java

Maven and the hook need JDK 25 in `JAVA_HOME`. The fixes for Windows, and the common errors such as
`release version 25 not supported`, are in *The hook in every clone* in the
[CI guide](ci.md#the-hook-in-every-clone).

### The backend does not start

- **`JWT_SECRET` is missing or too short:** fill it in `backend/.env` with at least 32 bytes
  (*Create the Local Environment File*).
- **`MFA_ENCRYPTION_KEY` is missing or not 32 bytes:** fill it in `backend/.env` with the output of
  `openssl rand -base64 32`.
- **The value has quotes:** the backend reads `.env` as a properties file, so the quotes become part
  of the value. Write `JWT_SECRET=abc...`, not `JWT_SECRET="abc..."`.
- **Flyway refuses the database after changing the data profile:** a database loaded with one
  profile (`dev`, `demo`, `perf`) does not start with another. Reset it (*Reset* in the
  [deployment guide](deployment.md)).
- **The port is taken:** see the ports in *Create the Local Environment File*: `POSTGRES_PORT` for the
  database, `SERVER_PORT` for the backend from the IDE or `./mvnw`.

### The Android build fails on Windows

The native build writes paths longer than 260 characters inside `node_modules\.pnpm`, and two tools
stop there even with long paths enabled in Windows:

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

### The phone cannot reach your machine

The app stops on an error screen of the Expo launcher, or the lists say that UrbanPulse cannot be
reached.

- **The address:** `apps/mobile/.env` must have your machine's address in the local network, never
  `localhost`, and the port of the way you run the system (*Which `.env` each way of running
  reads*). Start Metro with `pnpm dev:mobile --clear` after changing it.
- **The firewall:** if Windows asks whether Node.js, Java or Docker may use the network, allow
  private networks.
- **The router:** some routers isolate the devices of their Wi-Fi from each other. Connect the phone
  to your machine another way, for example sharing the connection over the USB cable.

### The map is empty or shows a notice

- **The base map is not built:** run the `tiles-builder` (*Build the Base Map* in the
  [deployment guide](deployment.md)).
- **It was built with another address:** the address in `TILES_BASE_URL` is written into the map.
  Build it again with the address the browser or the phone uses.
