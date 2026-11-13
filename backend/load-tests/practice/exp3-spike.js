// Experiment 3: an urban emergency. During heavy rain in Málaga the reports jump from 2 to SPIKE_RATE
// per second for a minute and come back down, while MAP_VUS staff users keep watching the map
// (MAP_VUS=0 runs the same spike without them). Every request is tagged with its phase (before,
// spike, after) to compare the latency and the errors of the reports in each one.
//   k6 run --tag testid=exp3-map-r1 -e MAP_VUS=40 -e RESULT=results/exp3-map-r1.json exp3-spike.js
import { sleep } from 'k6';
import exec from 'k6/execution';
import { OPEN_STATUSES, P95_MS, categoryIds, citizenTokens, get, pick, report, signIn, summaryTo } from './lib.js';

const SPIKE_RATE = Number(__ENV.SPIKE_RATE || 60);
const MAP_VUS = Number(__ENV.MAP_VUS || 40);
const BASE_RATE = 2;

// before 0-60 s, spike 70-130 s (with 10 s ramps on each side), after 140-230 s.
function phase() {
    const seconds = (Date.now() - exec.scenario.startTime) / 1000;
    if (seconds < 60) return 'before';
    if (seconds < 140) return 'spike';
    return 'after';
}

const scenarios = {
    reports: {
        executor: 'ramping-arrival-rate',
        exec: 'reportIncident',
        startRate: BASE_RATE,
        timeUnit: '1s',
        preAllocatedVUs: 30,
        maxVUs: 300,
        stages: [
            { duration: '1m', target: BASE_RATE },
            { duration: '10s', target: SPIKE_RATE },
            { duration: '1m', target: SPIKE_RATE },
            { duration: '10s', target: BASE_RATE },
            { duration: '1m30s', target: BASE_RATE },
        ],
    },
};
if (MAP_VUS > 0) {
    scenarios.map = { executor: 'constant-vus', exec: 'watchMap', vus: MAP_VUS, duration: '3m50s' };
}

const thresholds = { dropped_iterations: ['count>=0'] };
for (const p of ['before', 'spike', 'after']) {
    thresholds[`http_req_duration{operation:create,phase:${p}}`] = [`p(95)<${P95_MS}`];
    thresholds[`http_req_failed{operation:create,phase:${p}}`] = ['rate<0.01'];
    thresholds[`http_reqs{operation:create,phase:${p}}`] = ['count>=0'];
    if (MAP_VUS > 0) {
        thresholds[`http_req_duration{operation:map,phase:${p}}`] = [`p(95)<${P95_MS}`];
        thresholds[`http_req_failed{operation:map,phase:${p}}`] = ['rate<0.01'];
    }
}

export const options = { scenarios, thresholds, summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'] };

export function setup() {
    const operator = signIn('perf.operator1@example.com');
    return { operator, citizens: citizenTokens(30), categories: categoryIds(operator) };
}

export function reportIncident(data) {
    report(pick(data.citizens), data.categories, { operation: 'create', phase: phase() }, 'Flooded drain');
}

export function watchMap(data) {
    get(data.operator, `/incident-locations?${OPEN_STATUSES}`, { operation: 'map', phase: phase() });
    sleep(1);
}

export function handleSummary(data) {
    return summaryTo(__ENV.RESULT || 'results/exp3.json', data);
}
