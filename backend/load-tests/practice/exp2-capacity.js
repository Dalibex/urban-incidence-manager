// Experiment 2: what is the capacity of the system? The staff browsing mix (inbox, detail, map and
// dashboard) under three load models, chosen with MODE:
//   vus           ramping-vus: 10, 25, 50 and 100 users, one minute each, with think time
//   constant      constant-arrival-rate: RATE iterations per second for two minutes
//   ramping-rate  ramping-arrival-rate: from 20 to RATE_MAX iterations per second
// One request per iteration, so iterations per second are requests per second.
//   k6 run --tag testid=exp2-vus-r1 -e MODE=vus -e RESULT=results/exp2-vus-r1.json exp2-capacity.js
import { sleep } from 'k6';
import exec from 'k6/execution';
import { OPEN_STATUSES, P95_MS, get, incidentIds, pick, signIn, summaryTo } from './lib.js';

const MODE = __ENV.MODE || 'vus';
const RATE = Number(__ENV.RATE || 200);
const RATE_MAX = Number(__ENV.RATE_MAX || 1000);
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

const MODELS = {
    vus: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
            { duration: '20s', target: 10 }, { duration: '1m', target: 10 },
            { duration: '20s', target: 25 }, { duration: '1m', target: 25 },
            { duration: '20s', target: 50 }, { duration: '1m', target: 50 },
            { duration: '20s', target: 100 }, { duration: '1m', target: 100 },
            { duration: '20s', target: 0 },
        ],
    },
    constant: {
        executor: 'constant-arrival-rate',
        rate: RATE,
        timeUnit: '1s',
        // run.sh warms the backend up with 30 s of this model before each measurement.
        duration: __ENV.WARMUP ? '30s' : '2m',
        preAllocatedVUs: 50,
        maxVUs: MAX_VUS,
    },
    'ramping-rate': {
        executor: 'ramping-arrival-rate',
        startRate: 20,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: MAX_VUS,
        stages: [
            { duration: '1m', target: Math.round(RATE_MAX / 4) },
            { duration: '1m', target: Math.round(RATE_MAX / 2) },
            { duration: '1m', target: Math.round((RATE_MAX * 3) / 4) },
            { duration: '1m', target: RATE_MAX },
            { duration: '30s', target: RATE_MAX },
        ],
    },
};

export const options = {
    scenarios: { [MODE]: Object.assign({ exec: 'browse' }, MODELS[MODE]) },
    thresholds: {
        // QA-1 of docs/architecture.md. Informative: the point is where they stop being met.
        http_req_duration: [`p(95)<${P95_MS}`],
        http_req_failed: ['rate<0.01'],
        dropped_iterations: ['count>=0'],
    },
    summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const operator = signIn('perf.operator1@example.com');
    return { operator, ids: incidentIds(operator, 4) };
}

// The mix of a staff session: mostly the inbox and incidents, then the map and its dashboard.
export function browse(data) {
    const r = Math.random();
    // The users stage of ramping-vus, as a tag, to read each step of the ladder apart.
    const tags = MODE === 'vus' ? { vus_stage: String(stageOf(exec.instance.vusActive)) } : {};
    if (r < 0.4) get(data.operator, '/incidents?page=0&size=20', Object.assign({ operation: 'list' }, tags));
    else if (r < 0.7) get(data.operator, `/incidents/${pick(data.ids)}`, Object.assign({ operation: 'detail' }, tags));
    else if (r < 0.9) get(data.operator, `/incident-locations?${OPEN_STATUSES}`, Object.assign({ operation: 'map' }, tags));
    else get(data.operator, `/incident-statistics?${OPEN_STATUSES}`, Object.assign({ operation: 'statistics' }, tags));
    if (MODE === 'vus') sleep(1);
}

function stageOf(active) {
    if (active <= 10) return 10;
    if (active <= 25) return 25;
    if (active <= 50) return 50;
    return 100;
}

export function handleSummary(data) {
    return summaryTo(__ENV.RESULT || `results/exp2-${MODE}.json`, data);
}
