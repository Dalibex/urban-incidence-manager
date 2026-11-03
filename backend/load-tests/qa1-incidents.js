// Load test of QA-1 (docs/architecture.md, #155): the incident list, the detail and the history of
// each role, on the perf dataset. How to run it and its results: docs/datasets.md, "Measurements".
import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PASSWORD = __ENV.PERF_PASSWORD || 'urbanpulse-perf';
const DURATION = __ENV.DURATION || '5m';
const VUS_PER_ROLE = Number(__ENV.VUS_PER_ROLE || 5);
const P95_MS = Number(__ENV.P95_MS || 500);
// Seconds a user spends on a screen before opening the next one.
const THINK_TIME = Number(__ENV.THINK_TIME || 1);

// The analyst is left out: GET /incidents answers 403 to it.
const USERS = {
    citizen: 'perf.citizen1@example.com',
    operator: 'perf.operator1@example.com',
    administrator: 'perf.admin1@example.com',
    technician: 'perf.technician1@example.com',
};
const OPERATIONS = ['list', 'detail', 'history'];

function scenarioOf(role) {
    return {
        executor: 'constant-vus',
        vus: VUS_PER_ROLE,
        duration: DURATION,
        exec: 'browse',
        env: { ROLE: role },
        tags: { role },
    };
}

function thresholds() {
    const result = {
        http_req_failed: ['rate==0'],
        checks: ['rate==1'],
    };
    for (const role of Object.keys(USERS)) {
        for (const operation of OPERATIONS) {
            result[`http_req_duration{role:${role},operation:${operation}}`] = [`p(95)<${P95_MS}`];
        }
    }
    return result;
}

export const options = {
    scenarios: Object.fromEntries(Object.keys(USERS).map((role) => [role, scenarioOf(role)])),
    thresholds: thresholds(),
    summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

// Only the status on failure: the body of a sign-in holds tokens.
function json(response, expectedStatus, what) {
    if (response.status !== expectedStatus) {
        fail(`${what} answered ${response.status}`);
    }
    return response.json();
}

// Signs in once per role and keeps the incidents of its first page, so every role only opens
// incidents it may read. The access token (15 minutes) outlives a 5-minute run.
export function setup() {
    const sessions = {};
    for (const [role, email] of Object.entries(USERS)) {
        const signIn = http.post(`${BASE_URL}/sessions`, JSON.stringify({ email, password: PASSWORD }), {
            headers: { 'Content-Type': 'application/json' },
            tags: { role, operation: 'setup' },
        });
        const token = json(signIn, 201, `Sign-in of ${email}`).accessToken;
        const page = http.get(`${BASE_URL}/incidents?page=0&size=20`, {
            headers: { Authorization: `Bearer ${token}` },
            tags: { role, operation: 'setup' },
        });
        const ids = json(page, 200, `First page of ${role}`).content.map((incident) => incident.id);
        if (ids.length === 0) {
            fail(`${role} sees no incidents: is the perf profile loaded?`);
        }
        sessions[role] = { token, ids };
    }
    return sessions;
}

function get(path, token, operation) {
    const response = http.get(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${token}` },
        tags: { operation },
    });
    check(response, { [`${operation} answers 200`]: (r) => r.status === 200 });
}

export function browse(sessions) {
    const { token, ids } = sessions[__ENV.ROLE];
    const id = ids[Math.floor(Math.random() * ids.length)];

    get('/incidents?page=0&size=20', token, 'list');
    sleep(THINK_TIME);
    get(`/incidents/${id}`, token, 'detail');
    get(`/incidents/${id}/status-changes`, token, 'history');
    sleep(THINK_TIME);
}
