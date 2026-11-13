# Quality-attribute and observability practice

Load tests with [k6](https://grafana.com/docs/k6/latest/) for the four experiments of the course
practice, with their results seen in Grafana next to the backend's own metrics (Micrometer). The
measurements and their analysis are in [RESULTS.md](RESULTS.md).

| Experiment | Script | Question |
| --- | --- | --- |
| 1 | `exp1-operations.js` | Which operations are slowest? Each one alone, 10 VUs, one minute |
| 2 | `exp2-capacity.js` | What is the capacity? `ramping-vus` 10→100, `ramping-arrival-rate` and `constant-arrival-rate` |
| 3 | `exp3-spike.js` | What happens in an urban emergency? A spike of reports, with and without users on the map |
| 4 | `exp4-volume.js` | How does the volume of data change the queries? 1,000 to 1,000,000 incidents |

`lib.js` holds what they share: one sign-in per role in `setup()` (only failed sign-ins count
towards the backend's limit), the perf users and a position inside Málaga for every report.

## Stack

From `backend/`, with `.env` created from `.env.example` (set `GRAFANA_ADMIN_PASSWORD`, and
`SPRING_PROFILES_ACTIVE=perf`):

```bash
docker compose -p qaobs --profile app --profile observability up -d --build
```

| Service | Address | What it does |
| --- | --- | --- |
| `backend` | `http://localhost:8090` (`BACKEND_PORT`) | The API on the `perf` dataset; `/actuator/prometheus` gives its Micrometer metrics |
| `prometheus` | `http://localhost:9090` | Scrapes the backend every 5 s and receives the k6 results by remote write |
| `grafana` | `http://localhost:3000` | Two provisioned dashboards: *k6 load tests* and *UrbanPulse backend (Micrometer)* |

Every port is published on `127.0.0.1` only. Nothing of the profile starts with a plain `up`, and
the deployment Compose at the repository root does not include it.

## Running the experiments

`run.sh` runs each configuration three times, resets the database to the same `perf` rows before
every repetition that creates incidents, and warms the backend up for 30 s before measuring:

```bash
./run.sh exp1
./run.sh exp2
./run.sh exp2-constant 300
./run.sh exp3
./run.sh exp4 1000      # and 10000, 100000, 1000000
```

Each run is tagged `testid=<run>` in Prometheus (choose it in the *Run* variable of the k6
dashboard) and writes its k6 summary to `results/<run>.json`, which Git ignores. `python tables.py` turns the summaries
into the tables of RESULTS.md: the mean of the three runs with the lowest and highest value.

A single run by hand, with the k6 web dashboard and an HTML report instead of Grafana:

```bash
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=report.html k6 run exp1-operations.js
```
