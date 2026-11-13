// Experiment 4: how does the volume of data change the queries? The same queries, one after another
// with 10 VUs, on a database of PERF_INCIDENTS incidents (1,000 to 1,000,000; reset the database
// and restart the backend with each size). Run it before and after a schema change.
//   k6 run --tag testid=exp4-1000000-r1 -e RESULT=results/exp4-1000000-r1.json exp4-volume.js
import { OPEN_STATUSES, P95_MS, get, signIn, summaryTo } from './lib.js';

const VUS = Number(__ENV.VUS || 10);
const QUERY_SECONDS = Number(__ENV.QUERY_SECONDS || 30);
const GAP_SECONDS = 5;

// What the staff screens ask: filters, a deep page, a period ordered by date and the aggregates.
const QUERIES = {
    filtered: '/incidents?page=0&size=20&status=REPORTED&status=VALIDATED&priority=HIGH',
    'deep-page': '/incidents?page=40&size=20&sort=reportedAt,desc',
    'period-by-date': '/incidents?page=0&size=20&reportedFrom=2026-06-01T00:00:00Z&reportedTo=2026-07-01T00:00:00Z&sort=reportedAt,desc',
    map: `/incident-locations?${OPEN_STATUSES}`,
    statistics: `/incident-statistics?${OPEN_STATUSES}`,
    report: '/incident-reports?from=2025-11-01T00:00:00Z&to=2026-10-31T00:00:00Z&groupBy=district',
};

const scenarios = {};
const thresholds = {};
Object.keys(QUERIES).forEach((query, index) => {
    scenarios[query] = {
        executor: 'constant-vus',
        vus: VUS,
        duration: `${QUERY_SECONDS}s`,
        startTime: `${index * (QUERY_SECONDS + GAP_SECONDS)}s`,
        exec: 'run',
        env: { QUERY: query },
        tags: { operation: query },
    };
    thresholds[`http_req_duration{operation:${query}}`] = [`p(95)<${P95_MS}`];
    thresholds[`http_reqs{operation:${query}}`] = ['count>=0'];
    thresholds[`http_req_failed{operation:${query}}`] = ['rate<0.01'];
});

export const options = { scenarios, thresholds, summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'] };

export function setup() {
    return { operator: signIn('perf.operator1@example.com'), analyst: signIn('perf.analyst1@example.com') };
}

export function run(data) {
    const query = __ENV.QUERY;
    const token = query === 'report' ? data.analyst : data.operator;
    get(token, QUERIES[query], { operation: query });
}

export function handleSummary(data) {
    return summaryTo(__ENV.RESULT || 'results/exp4.json', data);
}
