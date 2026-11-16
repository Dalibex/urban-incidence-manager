// Experiment 1: which operations are slowest? Each operation alone, one after another, with a small
// and steady load (10 VUs, no think time) on the perf dataset, so the latency and throughput of
// every endpoint form a baseline.
//   k6 run --tag testid=exp1-r1 -e RESULT=results/exp1-r1.json exp1-operations.js
import { sleep } from 'k6';
import { OPEN_STATUSES, P95_MS, categoryIds, citizenTokens, get, incidentIds, pick, report, signIn, summaryTo } from './lib.js';

const VUS = Number(__ENV.VUS || 10);
const OP_SECONDS = Number(__ENV.OP_SECONDS || 60);
const GAP_SECONDS = 10;

// operation -> what one iteration does
const OPERATIONS = {
    list: (d) => get(d.operator, '/incidents?page=0&size=20', { operation: 'list' }),
    'list-filtered': (d) => get(d.operator, '/incidents?page=0&size=20&status=REPORTED&status=VALIDATED&priority=HIGH', { operation: 'list-filtered' }),
    detail: (d) => get(d.operator, `/incidents/${pick(d.ids)}`, { operation: 'detail' }),
    history: (d) => get(d.operator, `/incidents/${pick(d.ids)}/status-changes`, { operation: 'history' }),
    map: (d) => get(d.operator, `/incident-locations?${OPEN_STATUSES}`, { operation: 'map' }),
    statistics: (d) => get(d.operator, `/incident-statistics?${OPEN_STATUSES}`, { operation: 'statistics' }),
    report: (d) => get(d.analyst, '/incident-reports?from=2025-11-01T00:00:00Z&to=2026-10-31T00:00:00Z&groupBy=category', { operation: 'report' }),
    create: (d) => report(pick(d.citizens), d.categories, { operation: 'create' }, 'Experiment 1'),
};

const names = Object.keys(OPERATIONS);
const thresholds = {};
const scenarios = {};
names.forEach((operation, index) => {
    scenarios[operation] = {
        executor: 'constant-vus',
        vus: VUS,
        duration: `${OP_SECONDS}s`,
        startTime: `${index * (OP_SECONDS + GAP_SECONDS)}s`,
        exec: 'run',
        env: { OPERATION: operation },
        tags: { operation },
    };
    // Informative: they put every operation in the summary; a slow one does not stop the run.
    thresholds[`http_req_duration{operation:${operation}}`] = [`p(95)<${P95_MS}`];
    thresholds[`http_reqs{operation:${operation}}`] = ['count>=0'];
    thresholds[`http_req_failed{operation:${operation}}`] = ['rate<0.01'];
});

export const options = {
    scenarios,
    thresholds,
    summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const operator = signIn('perf.operator1@example.com');
    return {
        operator,
        analyst: signIn('perf.analyst1@example.com'),
        citizens: citizenTokens(10),
        ids: incidentIds(operator, 4),
        categories: categoryIds(operator),
    };
}

export function run(data) {
    OPERATIONS[__ENV.OPERATION](data);
    sleep(Number(__ENV.THINK_TIME || 0));
}

export function handleSummary(data) {
    return summaryTo(__ENV.RESULT || 'results/exp1.json', data);
}
