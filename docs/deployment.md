# 🐳 Deployment Guide

How UrbanPulse runs in containers: the Compose services, their configuration, their volumes and the
one-off processes. Today it covers the local Compose stack in `backend/docker-compose.yaml`, the
same image the CI tests ([ADR-0011](adr/0011-deployment-12factor.md)). #134 extends it with the web
entry point and a deployment outside a developer's machine.

To run the backend from the IDE or the terminal instead, see the
[development guide](development.md). The schema and its migrations are in the
[database guide](database.md), and the sample data in the [datasets guide](datasets.md).

## 🧩 Services

All the services are in `backend/docker-compose.yaml`. The Compose project is always called
`urbanpulse`, so the volumes keep their names if the folder moves.

| Service | Image | Profile | Port on your machine | Purpose |
| --- | --- | --- | --- | --- |
| `postgres` | `postgis/postgis` | — | `127.0.0.1:5432` (`POSTGRES_PORT`) | PostgreSQL with PostGIS ([ADR-0001](adr/0001-database.md), [ADR-0003](adr/0003-geospatial-data-management.md)) |
| `backend` | built from `backend/Dockerfile` | `app` | `127.0.0.1:8080` (`BACKEND_PORT`) | The Spring Boot API |
| `tiles-builder` | built from `tiles/Dockerfile` | `tiles` | — | One-off process that builds the base map (see *Build the Base Map*) |

A profile keeps a service out of a plain `docker compose up`:

- Without a profile, only `postgres` starts. This is what Spring Boot does when the backend runs
  from the IDE or `./mvnw`: it starts this file and gets only the database.
- `--profile app` adds the backend.
- `--profile tiles` is only for `docker compose run`: the base map builder runs once and exits.

Every port is published on `127.0.0.1`, so only your machine reaches them. A physical phone cannot;
see *Reach the API from a Phone*.

## ⚙️ Configuration

Compose reads `backend/.env`, which is never committed. Create it from `backend/.env.example`, as
*Create the Local Environment File* in the [development guide](development.md) explains. Write the
values without quotes.

| Variable | In `.env.example` | Secret | Used by | What it is |
| --- | --- | --- | --- | --- |
| `POSTGRES_DB` | `urbanpulse` | | `postgres`, `backend` | Database name |
| `POSTGRES_USER` | `urbanpulse` | | `postgres`, `backend` | Database user |
| `POSTGRES_PASSWORD` | `change_me` | ✓ | `postgres`, `backend` | Database password; change it |
| `POSTGRES_PORT` | `5432` | | `postgres` | Port on your machine |
| `BACKEND_PORT` | `8080` | | `backend` | Port on your machine; inside the container the API always listens on 8080 |
| `JWT_SECRET` | empty | ✓ | `backend` | Signs the access tokens, at least 32 bytes ([security guide](security.md)). The backend does not start without it |
| `JWT_ACCESS_TOKEN_TTL` | `PT15M` | | `backend` | Lifetime of an access token |
| `REFRESH_TOKEN_TTL` | `P14D` | | `backend` | Lifetime of a refresh token |
| `REFRESH_TOKEN_RETENTION` | `P30D` | | `backend` | How long an expired refresh token is kept before the daily clean-up deletes it ([security guide](security.md)) |
| `SPRING_PROFILES_ACTIVE` | empty | | `backend` | Data profile: empty (no sample data, as in production), `dev`, `demo` or `perf` ([datasets](datasets.md)) |
| `DEMO_PASSWORD` | empty | ✓ | `backend` | Password of the demo users; required with the `demo` profile |
| `PERF_INCIDENTS` | commented out | | `backend` | Incidents the `perf` profile generates, 100000 when it is not set. Leave it commented out rather than empty |
| `BOOTSTRAP_ADMIN_NAME` | empty | | `backend` | Name of the first administrator; only read by its one-off process (see *Create the First Administrator*) |
| `BOOTSTRAP_ADMIN_EMAIL` | empty | | `backend` | Email of the first administrator; only read by its one-off process |
| `BOOTSTRAP_ADMIN_PASSWORD` | empty | ✓ | `backend` | Password of the first administrator, with the rules of sign-up; empty it once the account exists |
| `TILES_BASE_URL` | `http://localhost:5173/tiles` | | `tiles-builder` | Address the clients load the map from, written into `style.json` |
| `STORAGE_BASE_PATH` | `/app/storage` | | `backend` | Folder for the evidence files, on the `evidence_data` volume ([ADR-0013](adr/0013-evidence-storage.md)) |
| `EVIDENCE_MAX_FILE_SIZE` | `10MB` | | `backend` | Maximum size of an evidence file; a larger one answers `413`. It also bounds the upload request (the file plus 64 KB) |
| `EVIDENCE_MAX_FILES_PER_INCIDENT` | `5` | | `backend` | Maximum evidence files per incident; one more answers `422` |

The backend does not start when an evidence limit is zero or negative.

PostgreSQL applies `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` only when it initializes
an empty volume. To change them later, reset the database (see *Reset*).

The backend image has no Docker Compose support: inside the container it gets the database
connection from `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD`, which `docker-compose.yaml` builds from the variables above.

## 🚀 Run the Backend in Docker

Use it to check that the backend works outside your IDE, or when you work on the clients and only
need the API. You do not need Java or Maven: the image compiles the jar. From `backend/`, with
`.env` created:

```bash
docker compose --profile app up --build
```

This starts the database and the backend. Check that the API is up:

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
- Do not run the backend from the IDE and in Docker at the same time: both use port 8080.
- With `-d` (in the background), follow the logs with `docker compose logs -f backend`. The backend
  writes its logs to stdout; there are no log files in the container.
- Stop everything with `Ctrl+C`, or with `docker compose --profile app down` if you started it with
  `-d`. The data stays in the volumes.

The image runs as an unprivileged user and stops cleanly on `SIGTERM`; the `build` CI job checks
both ([CI guide](ci.md#build--one-artifact-per-commit-and-smoke-test)).

### Build cache

The image downloads and its layers stay in Docker's build cache, which grows with every build that
changes something. `docker system df` shows its size (the *Build Cache* line). To free the space,
run `docker builder prune`: it does not touch images, containers or volumes, but the next build
takes as long as the first one.

## 📱 Reach the API from a Phone

The ports are published on `127.0.0.1` on purpose ([ADR-0011](adr/0011-deployment-12factor.md)):
the database and the API are not open to your local network. So the backend in Docker answers:

- the web app and `curl` on your machine, at `http://localhost:8080`;
- the Android emulator, at `http://10.0.2.2:8080`.

A physical phone cannot reach it. To try the mobile app on a phone, stop the `backend` container
and run the backend from the IDE or the terminal, which listens on every interface (see the
[development guide](development.md)). The database can stay in Docker. Set the address of your
machine in the local network in `frontend/apps/mobile/.env`, and allow Java on private networks if
the Windows firewall asks.

## 💾 Volumes

| Volume | Mounted in | Holds | Deleted by |
| --- | --- | --- | --- |
| `urbanpulse_postgres-data` | `postgres`, `/var/lib/postgresql` | Every table: users, incidents, history | `docker compose down -v` |
| `urbanpulse_evidence_data` | `backend`, `/app/storage` | Evidence files ([ADR-0013](adr/0013-evidence-storage.md)) | `docker compose down -v` |
| `urbanpulse_tiles-sources` | `tiles-builder`, `/data/sources` | OpenStreetMap downloads for the base map | `docker compose down -v` |

The base map itself is not in a volume: the builder writes it to `frontend/apps/web/public/tiles/`
(see *Build the Base Map*).

The backend keeps nothing on the container's own filesystem: removing or rebuilding the container
loses no data.

## ⚠️ Reset

`docker compose down` stops the containers and keeps the volumes. To delete all local data and
start from an empty database, from `backend/`:

```bash
docker compose --profile app down -v
```

`--profile app` also removes the backend container, if you started it. `-v` deletes every volume
of the table above, the base map downloads included, but not the map files already built.

Reset the database when:

- you change `POSTGRES_DB`, `POSTGRES_USER` or `POSTGRES_PASSWORD`;
- you want to leave a data profile, or switch from one to another ([datasets](datasets.md));
- you edit a migration that only exists on your branch ([database guide](database.md)).

To delete only the database and keep the base map downloads:

```bash
docker compose --profile app down
```

```bash
docker volume rm urbanpulse_postgres-data
```

## 🗺️ Build the Base Map

The maps use our own OpenStreetMap vector tiles of the province of Málaga, not a tile provider
([ADR-0016](adr/0016-self-hosted-osm-base-map.md)). They are never committed: the `tiles-builder`
service builds them as a one-off admin process (Twelve-Factor XII). From `backend/`, with Docker
running:

```bash
docker compose --profile tiles run --rm tiles-builder
```

It writes these files to `frontend/apps/web/public/tiles/`, a folder that Git ignores, and the web
development server (`pnpm dev:web`) serves them at `http://localhost:5173/tiles/`:

- `malaga.pmtiles`: the vector tiles, zoom 0 to 14, in the OpenMapTiles schema.
- `style.json`: the map style (OSM Bright), with its sprites and `fonts/`. A map loads it from
  `http://localhost:5173/tiles/style.json`; it references no external host and needs no key.

How it behaves:

- The first run downloads about 1.5 GB (the Andalucía extract from Geofabrik, water polygons and
  Natural Earth) into the `tiles-sources` volume. Later runs download only what changed, so run it
  again whenever you want newer OpenStreetMap data.
- The new tiles replace the old ones only if the build succeeds, `pmtiles verify` accepts the file
  and it is not suspiciously small. Otherwise the previous files stay as they were and the command
  exits with an error; the last `tiles-builder: ERROR` line of the log says which step failed.
- `TILES_BASE_URL` is the address written into `style.json`. A phone cannot reach `localhost`: to
  load the map on a phone, set the address of your machine in the local network, start the web app
  with `pnpm dev:web --host` and run the builder again.
- Every version is pinned in `tiles/Dockerfile` (Planetiler, the style, the fonts and the images).
  To update one, change its version and its checksum together.
- `docker volume rm urbanpulse_tiles-sources` frees the downloaded sources. The next run downloads
  them again.

## 🔑 Create the First Administrator

Sign-up only creates citizens, and staff accounts are created by an administrator, so a database
without the `dev` or `demo` dataset has no account that can manage the others. A one-off admin
process (#126, Twelve-Factor XII) creates the first administrator with the same image and
configuration as the backend. The rules it follows are in the
[security guide](security.md#first-administrator).

1. Set `BOOTSTRAP_ADMIN_NAME`, `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` in `.env`,
   or in the environment of the shell. The values follow the rules of sign-up: the password needs
   8 characters or more. Leave `$` and `\` out of it: Compose reads `$` in `.env` as the start of a
   variable and the IDE reads `\` as an escape, so the account would get a password you did not
   type.
2. From `backend/`, with Docker running:

   ```bash
   docker compose --profile app run --rm backend --spring.profiles.include=bootstrap-admin
   ```

3. Sign in on the web with that email and password, then empty `BOOTSTRAP_ADMIN_PASSWORD`.

How it behaves:

- No web server starts. Flyway applies the pending migrations, the account is created and the
  process ends with exit code `0`. The log says whether it created the account or found an enabled
  administrator.
- Running it again is safe: with an enabled administrator in the database it changes nothing and
  does not even check the variables, so it succeeds with the password already emptied. Further
  staff accounts are created by that administrator (#40).
- Without an administrator, it fails with the reason in the log, and changes nothing, when a value
  breaks a rule of sign-up or the email belongs to another account. That account is never
  promoted.
- Run it once at a time: two runs at the same moment with different emails could create two
  administrators.
- If the administrator cannot sign in (a wrong password, and there is no password change yet),
  disable the account in the database (`UPDATE users SET enabled = false WHERE email = '...'`) and
  run the process again with another email: the old email now belongs to another account.
- The password never appears in the output or in the logs.
- `--spring.profiles.include` adds `bootstrap-admin` to `SPRING_PROFILES_ACTIVE`, so a database that
  loaded a dataset keeps its data profile and Flyway still validates it
  ([datasets](datasets.md)). `--spring.profiles.active` would replace it. `bootstrap-admin` is not
  a data profile: it loads no data and goes with any of them.
- From the IDE, add `bootstrap-admin` to the **Active profiles** of the run configuration, next to
  the data profile of your database if it has one. From the terminal, that option sets every
  active profile, so list the data profile too:
  `./mvnw spring-boot:run "-Dspring-boot.run.profiles=bootstrap-admin"` on an empty database,
  `"-Dspring-boot.run.profiles=dev,bootstrap-admin"` on one that loaded `dev`.

## 🔄 Update and Roll Back

- To run new code, pull it and start again with `--build`. Flyway applies the new migrations when
  the backend starts ([database guide](database.md)).
- Going back to an older image does not undo its migrations: the database keeps the newer schema,
  and an older backend may not start on it. Locally, reset the database; anywhere else, roll
  forward with a new migration.
