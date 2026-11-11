# 🐳 Deployment Guide

How UrbanPulse runs in containers: the two Compose files, their configuration, their volumes, the
one-off processes and what to do when something fails. The images are the ones the CI builds and
tests ([ADR-0011](adr/0011-deployment-12factor.md)).

| File | What it is for | Published ports |
| --- | --- | --- |
| `docker-compose.yaml` (repository root) | **Deployment:** the whole system on one machine, behind nginx ([ADR-0017](adr/0017-reverse-proxy.md)). Also the way to try the full system on your machine | Only `web`, on `HTTP_PORT` |
| `backend/docker-compose.yaml` | **Development:** the database for the IDE, the backend image and the base map for the Vite development server. Never used on a server | `127.0.0.1` only |

To run the backend from the IDE or the terminal instead, see the
[development guide](development.md). The schema and its migrations are in the
[database guide](database.md), and the sample data in the [datasets guide](datasets.md).

## 🌐 Deploy the Whole System

You only need Docker Engine (or Docker Desktop) with Compose v2, and Git: the images compile the
backend and the web app, so the machine needs no Java and no Node. From the repository root:

1. Create `.env` from `.env.example`, which holds no development value and no secret:

   ```bash
   cp .env.example .env
   ```

   Fill in `POSTGRES_PASSWORD` and `JWT_SECRET` (`openssl rand -base64 48`). Set `HTTP_PORT`, and
   `TILES_BASE_URL` to the address the browsers and phones use to reach this machine, plus
   `/tiles`. Every variable is in *Configuration*.
2. Build the images and start the system:

   ```bash
   docker compose up -d --build
   ```

   The first build takes several minutes: it downloads Maven, the Java dependencies and the client
   packages. `docker compose ps` lists `postgres`, `backend` and `web`, and only `web` shows a port.
3. Create the first administrator (*Create the First Administrator*):

   ```bash
   docker compose run --rm backend --spring.profiles.include=bootstrap-admin
   ```

4. Build the base map (*Build the Base Map*):

   ```bash
   docker compose --profile tiles run --rm tiles-builder
   ```

5. Open `http://<this machine>:<HTTP_PORT>` and sign in with the administrator.

The mobile app needs the same address in `EXPO_PUBLIC_API_BASE_URL`, followed by `/api/v1`, when
it is built.

The web image is built without any `.env` (`frontend/.dockerignore` leaves them out), so the quick
sign-in of the demo (`VITE_QUICK_SIGN_IN`) is always off in it: the demo password never goes into a
deployed build ([twelve-factor](twelve-factor.md#iii-config)). Sign in with the email and password
of each demo user instead ([demo script](demo.md)).

**HTTPS is not set up yet.** ADR-0017 requires it before the system is reachable from the
internet: without it, passwords and tokens travel in plain text, and release builds of the mobile
app refuse plain HTTP. Use this deployment on a machine or a network you trust, such as the demo
LAN, until the cloud deployment decides the certificate.

### What nginx Serves

`web` is the image of `frontend/Dockerfile`: an unprivileged nginx (UID 101) with the production
build of the web app and the configuration of `frontend/nginx.conf`.

| Path | What answers |
| --- | --- |
| `/api/` | The backend. The browser calls the API on its own origin, so there is no CORS |
| `/openapi.yml`, `/swagger-ui.html`, `/v3/api-docs` | The API documentation of the backend. It is public in every environment ([security guide](security.md#permission-matrix)); [the API guide](api.md) says how to turn off Swagger UI |
| `/tiles/` | The base map, from the `tiles` volume, with `Range` requests (`206`) and one hour of cache |
| `/assets/` | The files of the web build, cached for a year: their names change with their content |
| Anything else | The web app. A client route such as `/inbox` gets `index.html`, so reloading it works |

The actuator of the backend is not proxied: `/actuator/health` is only reachable inside the
Compose network.

The limits of nginx:

| Case | Answer |
| --- | --- |
| A request body over 10 MB plus 64 KB, the largest upload the backend accepts (`EVIDENCE_MAX_FILE_SIZE`, [ADR-0013](adr/0013-evidence-storage.md)) | `413`, before it reaches the backend. If you raise `EVIDENCE_MAX_FILE_SIZE`, raise `client_max_body_size` in `nginx.conf` too |
| Sign-up, sign-in, refresh and password change: more than 30 a minute from one address, after a burst of 20 | `429`, without reaching the backend ([security guide](security.md#rate-limits)) |
| The backend does not accept the connection in 5 s, or does not answer for 30 s | `504` |
| The backend is stopped | `502` at once. The web app still loads and shows the error |

nginx looks the backend up on each request, so it starts, and serves the web app, while the
backend is down.

## 🧩 Services of the Development File

This section, *Run the Backend in Docker* and *Reach the API from a Phone* are about
`backend/docker-compose.yaml`. Its Compose project is always called `urbanpulse`, so the volumes
keep their names if the folder moves. The deployment file is the project `urbanpulse-deploy`, so
the two never share a container or a volume on the same machine.

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

Each Compose file reads the `.env` next to it, which is never committed: `backend/.env` for
development, created from `backend/.env.example` as *Create the Local Environment File* in the
[development guide](development.md) explains, and `.env` at the repository root for the
deployment, created from `.env.example`. Write the values without quotes, and leave `$` and `\`
out of them: Compose reads `$` as the start of a variable.

The deployment `.env.example` is the example without secrets: every secret is empty, there is no
sample data and nothing but nginx publishes a port. Its secrets are `POSTGRES_PASSWORD`,
`JWT_SECRET`, `DEMO_PASSWORD` (only with the `demo` profile) and `BOOTSTRAP_ADMIN_PASSWORD` (only
while you create the first administrator).

| Variable | Development (`backend/.env.example`) | Deployment (`.env.example`) | Secret | Used by | What it is |
| --- | --- | --- | --- | --- | --- |
| `HTTP_PORT` | — | `8000` | | `web` | Port of the entry point on the machine, the only published port. Use `80` on a server |
| `POSTGRES_DB` | `urbanpulse` | `urbanpulse` | | `postgres`, `backend` | Database name |
| `POSTGRES_USER` | `urbanpulse` | `urbanpulse` | | `postgres`, `backend` | Database user |
| `POSTGRES_PASSWORD` | `change_me` | empty | ✓ | `postgres`, `backend` | Database password. PostgreSQL does not start on an empty volume without it |
| `POSTGRES_PORT` | `5432` | — | | `postgres` | Port on your machine, development only |
| `BACKEND_PORT` | `8080` | — | | `backend` | Port on your machine, development only; inside the container the API always listens on 8080 |
| `JWT_SECRET` | empty | empty | ✓ | `backend` | Signs the access tokens, at least 32 bytes ([security guide](security.md)). The backend does not start without it |
| `JWT_ACCESS_TOKEN_TTL` | `PT15M` | `PT15M` | | `backend` | Lifetime of an access token |
| `REFRESH_TOKEN_TTL` | `P14D` | `P14D` | | `backend` | Lifetime of a refresh token |
| `REFRESH_TOKEN_RETENTION` | `P30D` | `P30D` | | `backend` | How long an expired refresh token is kept before the daily clean-up deletes it ([security guide](security.md)) |
| `SPRING_PROFILES_ACTIVE` | empty | empty | | `backend` | Data profile: empty (no sample data, as in production), `dev`, `demo` or `perf` ([datasets](datasets.md)) |
| `DEMO_PASSWORD` | empty | empty | ✓ | `backend` | Password of the demo users; required with the `demo` profile |
| `PERF_INCIDENTS` | commented out | — | | `backend` | Incidents the `perf` profile generates, 100000 when it is not set. Leave it commented out rather than empty |
| `BOOTSTRAP_ADMIN_NAME` | empty | empty | | `backend` | Name of the first administrator; only read by its one-off process (see *Create the First Administrator*) |
| `BOOTSTRAP_ADMIN_EMAIL` | empty | empty | | `backend` | Email of the first administrator; only read by its one-off process |
| `BOOTSTRAP_ADMIN_PASSWORD` | empty | empty | ✓ | `backend` | Password of the first administrator, with the rules of sign-up; empty it once the account exists |
| `TILES_BASE_URL` | `http://localhost:5173/tiles` | `http://localhost:8000/tiles` | | `tiles-builder` | Address the clients load the map from, written into `style.json`: the Vite server in development, the entry point in the deployment |
| `STORAGE_BASE_PATH` | `/app/storage` | `/app/storage` | | `backend` | Folder for the evidence files, on the `evidence_data` volume ([ADR-0013](adr/0013-evidence-storage.md)) |
| `EVIDENCE_MAX_FILE_SIZE` | `10MB` | `10MB` | | `backend` | Maximum size of an evidence file; a larger one answers `413`. It also bounds the upload request (the file plus 64 KB), and so does `client_max_body_size` in `frontend/nginx.conf` |
| `EVIDENCE_MAX_FILES_PER_INCIDENT` | `5` | `5` | | `backend` | Maximum evidence files per incident; one more answers `422` |

The backend does not start when an evidence limit is zero or negative.

PostgreSQL applies `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` only when it initializes
an empty volume. To change them later, reset the database (see *Reset*).

The backend image has no Docker Compose support: inside the container it gets the database
connection from `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD`, which both Compose files build from the variables above.

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

Each volume name starts with its Compose project: `urbanpulse` for the development file,
`urbanpulse-deploy` for the deployment one.

| Volume | Mounted in | Holds | Deleted by |
| --- | --- | --- | --- |
| `<project>_postgres-data` | `postgres`, `/var/lib/postgresql` | Every table: users, incidents, history | `docker compose down -v` |
| `<project>_evidence_data` | `backend`, `/app/storage` | Evidence files ([ADR-0013](adr/0013-evidence-storage.md)) | `docker compose down -v` |
| `<project>_tiles-sources` | `tiles-builder`, `/data/sources` | OpenStreetMap downloads for the base map | `docker compose down -v` |
| `urbanpulse-deploy_tiles` | `tiles-builder`, `/tiles`, and `web`, `/srv/tiles` (read-only) | The base map nginx serves. Deployment only | `docker compose down -v` |

In development the base map is not in a volume: the builder writes it to
`frontend/apps/web/public/tiles/` (see *Build the Base Map*).

The backend and nginx keep nothing on the container's own filesystem: removing or rebuilding a
container loses no data. `docker compose restart`, and `down` followed by `up`, keep every volume.

## 🗄️ Back Up and Restore

The data that cannot be rebuilt is the database and the evidence files; the base map is built again
with `tiles-builder`. The evidence files are named by the database, so back up both at the same
moment and restore them together. Run these commands from the repository root, against the
deployment file. On Windows, run them in PowerShell: Git Bash rewrites paths such as `/tmp/...`
before Docker sees them.

### Back up

```bash
docker compose exec postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -N tiger -N tiger_data -N topology -f /tmp/urbanpulse.dump'
docker compose cp postgres:/tmp/urbanpulse.dump ./urbanpulse.dump
docker compose exec postgres rm /tmp/urbanpulse.dump
docker compose cp backend:/app/storage ./evidence
```

This gives `urbanpulse.dump` and an `evidence/` folder, which must not exist before. Keep them off
this machine. The `tiger`
and `topology` schemas are left out because the PostGIS image creates them in every new database.

### Restore

On empty volumes, for example on a new machine or after `docker compose down -v`. The backend must
not start before the database is restored: on an empty database it would apply the migrations
itself.

```bash
docker compose up -d --wait postgres
docker compose cp ./urbanpulse.dump postgres:/tmp/urbanpulse.dump
docker compose exec postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner /tmp/urbanpulse.dump && rm /tmp/urbanpulse.dump'
docker compose up -d
docker compose cp ./evidence/. backend:/app/storage
docker compose exec -u root backend chown -R urbanpulse:urbanpulse /app/storage
```

The last command gives the files back to the user of the backend, because `docker compose cp`
writes them as root. Sign in and open an incident with photos to check the restore.

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

In the deployment, `docker compose down -v` from the repository root deletes the database, the
evidence and the base map of that machine. Back up first (*Back Up and Restore*).

## 🗺️ Build the Base Map

The maps use our own OpenStreetMap vector tiles of the province of Málaga, not a tile provider
([ADR-0016](adr/0016-self-hosted-osm-base-map.md)). They are never committed: the `tiles-builder`
service builds them as a one-off admin process (Twelve-Factor XII).

In the deployment, from the repository root:

```bash
docker compose --profile tiles run --rm tiles-builder
```

It writes the files into the `tiles` volume, and nginx serves them at `<TILES_BASE_URL>/`
at once, with no restart. Until it has run once, the web app works without its base map.

In development, from `backend/`, with Docker running:

```bash
docker compose --profile tiles run --rm tiles-builder
```

It writes these files to `frontend/apps/web/public/tiles/`, a folder that Git ignores, and the web
development server (`pnpm dev:web`) serves them at `http://localhost:5173/tiles/`:

- `malaga.pmtiles`: the vector tiles, zoom 0 to 14, in the OpenMapTiles schema.
- `style.json`: the map style (OSM Bright), with its sprites and `fonts/`. A map loads it from
  `http://localhost:5173/tiles/style.json`; it references no external host and needs no key.
  The web map loads it from the same origin by default (`/tiles/style.json`); set
  `VITE_MAP_STYLE_URL` in `frontend/apps/web/.env` only to load it from elsewhere.

How it behaves, with either file:

- The first run downloads about 1.5 GB (the Andalucía extract from Geofabrik, water polygons and
  Natural Earth) into the `tiles-sources` volume. Later runs download only what changed, so run it
  again whenever you want newer OpenStreetMap data.
- The new tiles replace the old ones only if the build succeeds, `pmtiles verify` accepts the file
  and it is not suspiciously small. Otherwise the previous files stay as they were and the command
  exits with an error; the last `tiles-builder: ERROR` line of the log says which step failed.
- `TILES_BASE_URL` is the address written into `style.json`. A phone cannot reach `localhost`: to
  load the map on a phone, set the address of your machine in the local network (in development,
  also start the web app with `pnpm dev:web --host`) and run the builder again.
- Every version is pinned in `tiles/Dockerfile` (Planetiler, the style, the fonts and the images).
  To update one, change its version and its checksum together.
- `docker volume rm <project>_tiles-sources` frees the downloaded sources. The next run downloads
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
2. With Docker running, in the deployment, from the repository root:

   ```bash
   docker compose run --rm backend --spring.profiles.include=bootstrap-admin
   ```

   In development, from `backend/`:

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
- If the administrator forgot the password (there is no recovery by email), disable the account in the database (`UPDATE users SET enabled = false WHERE email = '...'`) and
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

- To run new code, pull it and start again with `--build`. In the deployment, from the repository
  root: `git pull`, then `docker compose up -d --build`. Compose recreates only the containers whose
  image changed, and Flyway applies the new migrations when the backend starts
  ([database guide](database.md)). Back up first (*Back Up and Restore*) when the update brings
  migrations.
- Going back to an older image does not undo its migrations: the database keeps the newer schema,
  and an older backend may not start on it. Locally, reset the database; anywhere else, roll
  forward with a new migration, or restore the backup taken before the update.
- Going back to an older `web` image is always safe: nginx has no migrations and keeps no data.

## 🔁 One Backend Replica

The deployment runs one backend container, and it must stay one ([ADR-0011](adr/0011-deployment-12factor.md)):

- the sign-up and sign-in limits (`AttemptLimiter`) count in the memory of the process. With two
  replicas each one would allow the full limit, so an attacker would get twice the attempts
  ([security guide](security.md#rate-limits));
- the daily clean-up of refresh tokens would run once per replica.

A second replica needs those counters in a shared store, which is one of the triggers of
[ADR-0022](adr/0022-redis-from-measurements.md) for Redis. A restart of the backend resets the
counters.

## 🛟 Restarts and Logs

- Every service has `restart: unless-stopped`: when a process ends, Docker starts it again, also
  after the machine reboots. A container you stop yourself stays stopped.
- `restart` only acts when the process ends. A process that hangs without dying has to be restarted
  by hand.
- Docker keeps at most three log files of 10 MB per container, so the logs cannot fill the disk.
  `docker compose logs --tail=100 <service>` reads them.
- `docker compose stop` sends each process its stop signal and waits: 30 s for the backend, which
  lets the requests in progress finish, 10 s for the others. Its log ends with `Graceful shutdown
  complete`.

## 🚨 If nginx Goes Down

nginx is the single entry point, so if it stops, the web app, the API and the base map stop for
everyone. **No data is lost:** nginx keeps no state, and the backend and the database keep running.
The team accepts this risk for now (#159): on one machine a second nginx would add nothing, because
if the machine falls, both fall.

1. `docker compose ps`, to see which containers are alive.
2. `docker compose logs --tail=100 web`, to read why it fell.
3. `docker compose restart web`.
4. If the new version is what is broken, deploy the previous image (see *Update and Roll Back*). It
   is safe, because nginx has no migrations.
5. **Do not publish the port of the backend as a shortcut:** it would be exposed without the limits
   of nginx, and without the client address that #200 relies on.

## 👥 Shared Limits

The sign-up and sign-in limits of the backend tell clients apart by their address
([security guide](security.md#rate-limits)), and so does the limit of nginx. Several people can
share one address:

- **Behind nginx, the backend sees the address of nginx.** Until #200 passes it the real client
  address, every client shares the backend limits: the 10 sign-ups per hour are for everyone, and
  five wrong passwords for an email block that email for everyone. Planned: #200.
- **On Docker Desktop** (Windows and macOS), Docker hides the client address, so nginx sees the
  same address for every client and its limit is shared too. On a Linux machine the real address
  reaches nginx.
- **On a shared network,** such as the university wifi or some mobile operators, everyone behind
  one public address shares the limits.

For a demo, create the users beforehand with the `demo` dataset ([demo script](demo.md)) instead
of signing them up during the demo.
