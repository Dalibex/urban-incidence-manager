#!/usr/bin/env bash
# Runs the experiments of the quality-attribute practice (README.md in this folder) from
# backend/load-tests/practice, with the stack of `docker compose -p qaobs --profile app
# --profile observability up -d` already running. Each configuration runs three times.
#   ./run.sh exp1 | exp2 | exp2-constant RATE | exp3 | exp4 SIZE
# Every run sends its metrics to Prometheus under testid=<run> and writes results/<run>.json.
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE=(docker compose -p "${COMPOSE_PROJECT:-qaobs}" -f ../../docker-compose.yaml --profile app --profile observability)
REPETITIONS="${REPETITIONS:-3}"
# The port the stack publishes the backend on: BACKEND_PORT of backend/.env, 8080 if it is not set.
BACKEND_PORT="${BACKEND_PORT:-$(sed -n 's/^BACKEND_PORT=//p' ../../.env 2>/dev/null | tr -d '\r')}"
export BACKEND_PORT="${BACKEND_PORT:-8080}"
# The experiments report incidents: without this, the weather worker would call the real Open-Meteo
# for each one (ADR-0018 forbids it in a load test).
export WEATHER_ENRICHMENT_ENABLED=false
export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
export K6_PROMETHEUS_RW_TREND_STATS="p(50),p(95),p(99),max"
mkdir -p results logs

log() { echo "$(date +%H:%M:%S) $*" | tee -a logs/run.log; }

# A fresh perf database of PERF_INCIDENTS incidents, so every repetition starts from the same rows.
reset_database() {
    local size="${1:-100000}"
    log "reset: perf dataset of ${size} incidents"
    "${COMPOSE[@]}" rm -sf backend postgres >/dev/null
    docker volume rm -f "${COMPOSE_PROJECT:-qaobs}_postgres-data" >/dev/null
    PERF_INCIDENTS="$size" "${COMPOSE[@]}" up -d postgres backend >/dev/null 2>&1
    for _ in $(seq 1 240); do
        if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:${BACKEND_PORT}/actuator/health)" = 200 ]; then
            log "reset: backend ready"
            return
        fi
        sleep 5
    done
    log "reset: the backend did not start"
    exit 1
}

# Thirty seconds of the browsing mix, not measured, so the JIT and the caches are warm.
warm_up() {
    k6 run -q --tag testid=warmup -e MODE=constant -e RATE=20 -e RESULT=logs/warmup.json \
        -e WARMUP=1 exp2-capacity.js >/dev/null 2>&1 || true
}

run() {
    local id="$1"; shift
    log "run: ${id}"
    k6 run -q --tag "testid=${id}" -o experimental-prometheus-rw -e "RESULT=results/${id}.json" "$@" \
        >"logs/${id}.txt" 2>&1 || true
    log "done: ${id}"
}

case "${1:-}" in
    exp1)
        for r in $(seq 1 "$REPETITIONS"); do
            reset_database 100000; warm_up
            run "exp1-r${r}" exp1-operations.js
        done ;;
    exp2)
        reset_database 100000; warm_up
        for r in $(seq 1 "$REPETITIONS"); do run "exp2-vus-r${r}" -e MODE=vus exp2-capacity.js; done
        for r in $(seq 1 "$REPETITIONS"); do run "exp2-ramping-rate-r${r}" -e MODE=ramping-rate -e "RATE_MAX=${RATE_MAX:-600}" exp2-capacity.js; done ;;
    exp2-constant)
        for r in $(seq 1 "$REPETITIONS"); do run "exp2-constant-${2}-r${r}" -e MODE=constant -e "RATE=${2}" exp2-capacity.js; done ;;
    exp3)
        for map in 40 0; do
            for r in $(seq 1 "$REPETITIONS"); do
                reset_database 100000; warm_up
                run "exp3-map${map}-r${r}" -e "MAP_VUS=${map}" -e "SPIKE_RATE=${SPIKE_RATE:-60}" exp3-spike.js
            done
        done ;;
    exp4)
        reset_database "$2"; warm_up
        for r in $(seq 1 "$REPETITIONS"); do run "exp4-${2}${SUFFIX:-}-r${r}" exp4-volume.js; done ;;
    *)
        echo "usage: $0 exp1 | exp2 | exp2-constant RATE | exp3 | exp4 SIZE" >&2
        exit 2 ;;
esac
