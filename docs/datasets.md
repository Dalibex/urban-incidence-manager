# 📊 Datasets and Measurements

The datasets UrbanPulse loads for a purpose other than development, and the measurements taken
with them. The development dataset (`dev` profile) is in the [development guide](development.md);
how every data profile is loaded, reloaded and changed is in the
*Data Profiles* in the [database guide](database.md).

| Profile | Purpose | Where |
| --- | --- | --- |
| `dev` | Try the API and the clients while developing | [Development guide](development.md) |
| `demo` | Tell one story in front of an audience | *Demo Dataset* below and the [demo script](demo.md) |

## 🎬 Demo Dataset

The `demo` Spring profile loads the curated story of the demo (#54): one user per role and fifteen
incidents in Málaga covering every status, each with a history allowed by
[ADR-0020](adr/0020-incident-lifecycle-transitions.md). The [demo script](demo.md) lists its users
and incidents and how to walk through them. It is separate from the development dataset: the
development data keeps the cases developers need (several citizens and technicians, urban context,
assets, notifications), the demo data tells one story that is easy to follow.

It follows the pattern of every data profile (*Data Profiles* in the
[database guide](database.md)):
`application-demo.yaml` adds `classpath:db/demo-data` to the Flyway locations,
`db/demo-data/R__demo_data.sql` is a repeatable migration, and `DemoDatasetIT` loads it on its own
PostGIS container.

- Activate it as the `dev` profile (see *Activate it* in the
  [development guide](development.md)), with `demo` instead of `dev`, on an empty database. To show
  the mobile app on a phone, run the backend from the IDE or the terminal (see the
  [demo script](demo.md)).
- Every demo user signs in with the password in the `DEMO_PASSWORD` environment variable
  (`backend/.env`, or the environment of the container). The backend does not start with the
  `demo` profile without it. Flyway passes it to the script as the `demo_password` placeholder and
  PostgreSQL hashes it with bcrypt (the `pgcrypto` extension, which the script enables), so no
  password or hash is in the repository. To change the password, reset the database and start
  again with the new value.
- Restarting keeps what was created during the demo; only a change to `R__demo_data.sql` or a
  reset inserts the story again.

## 📏 Measurements

Results of the quality attribute measurements. A number without its conditions cannot be compared
or repeated, so every measurement records:

| Field | Example |
| --- | --- |
| Operation | `GET /incidents?page=0&size=20` as an operator |
| Data | Profile and volume |
| Load | Concurrent users or requests per second, and duration |
| Environment | Machine, Docker resources, versions of the backend and the database |
| Result | Percentiles (p50, p95, p99), never only the average |
| Date and commit | When and on which commit it was measured |

No measurements have been recorded yet.
