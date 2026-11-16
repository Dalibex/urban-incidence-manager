// Shared helpers of the quality-attribute experiments (README.md in this folder).
// Every script signs in once in setup(): only failed sign-ins count towards the backend's limit,
// so repeating the runs never locks an account, and the requests measured are the operations.
import http from 'k6/http';
import { check, fail } from 'k6';

// BACKEND_PORT is the one of backend/.env (8080 by default); run.sh exports it.
export const BASE_URL = __ENV.BASE_URL || `http://localhost:${__ENV.BACKEND_PORT || '8080'}/api/v1`;
export const PASSWORD = __ENV.PERF_PASSWORD || 'urbanpulse-perf';
export const P95_MS = Number(__ENV.P95_MS || 500);

// The statuses the map shows by default (every open incident).
export const OPEN_STATUSES = 'status=REPORTED&status=VALIDATED&status=ASSIGNED&status=IN_PROGRESS&status=RESOLVED&status=REOPENED';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Only the status on failure: a sign-in body holds tokens.
export function expectJson(response, status, what) {
    if (response.status !== status) {
        fail(`${what} answered ${response.status}`);
    }
    return response.json();
}

export function signIn(email) {
    const response = http.post(`${BASE_URL}/sessions`, JSON.stringify({ email, password: PASSWORD }), {
        headers: JSON_HEADERS,
        tags: { operation: 'setup' },
    });
    return expectJson(response, 201, `Sign-in of ${email}`).accessToken;
}

export function auth(token, extraHeaders) {
    return { headers: Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders || {}) };
}

// The ids of the first pages of the operator's inbox, to open existing incidents.
export function incidentIds(token, pages) {
    const ids = [];
    for (let page = 0; page < pages; page++) {
        const body = expectJson(
            http.get(`${BASE_URL}/incidents?page=${page}&size=50`, Object.assign(auth(token), { tags: { operation: 'setup' } })),
            200,
            `Inbox page ${page}`,
        );
        body.content.forEach((incident) => ids.push(incident.id));
    }
    if (ids.length === 0) {
        fail('The operator sees no incidents: is the perf profile loaded?');
    }
    return ids;
}

export function categoryIds(token) {
    const body = expectJson(
        http.get(`${BASE_URL}/categories`, Object.assign(auth(token), { tags: { operation: 'setup' } })),
        200,
        'Categories',
    );
    return (Array.isArray(body) ? body : body.content || []).map((category) => category.id);
}

// Signs in the first `count` citizens of the perf dataset.
export function citizenTokens(count) {
    const tokens = [];
    for (let i = 1; i <= count; i++) {
        tokens.push(signIn(`perf.citizen${i}@example.com`));
    }
    return tokens;
}

export function pick(list) {
    return list[Math.floor(Math.random() * list.length)];
}

// A point inside the city of Málaga.
export function malagaPosition() {
    return {
        latitude: 36.69 + Math.random() * 0.06,
        longitude: -4.48 + Math.random() * 0.08,
        accuracy: 5 + Math.random() * 20,
    };
}

// k6 names each request by its URL; one name per operation keeps a series per operation, not per id.
function named(tags) {
    return Object.assign({ name: tags.operation }, tags);
}

export function report(token, categories, tags, titlePrefix) {
    const payload = {
        title: `${titlePrefix || 'Load test'} ${__VU}-${__ITER}`,
        description: 'Incident created by a load test of the quality-attribute practice.',
        location: malagaPosition(),
    };
    if (categories && categories.length > 0 && Math.random() < 0.8) {
        payload.categoryId = pick(categories);
    }
    const response = http.post(`${BASE_URL}/incidents`, JSON.stringify(payload), Object.assign(auth(token, JSON_HEADERS), { tags: named(tags) }));
    check(response, { 'report created (201)': (r) => r.status === 201 });
    return response;
}

export function get(token, path, tags) {
    const response = http.get(`${BASE_URL}${path}`, Object.assign(auth(token), { tags: named(tags) }));
    check(response, { 'status 200': (r) => r.status === 200 });
    return response;
}

// Writes the whole k6 summary as JSON (the tables are built from it) and a short text summary.
export function summaryTo(file, data) {
    const lines = [];
    for (const [name, metric] of Object.entries(data.metrics)) {
        if (!name.startsWith('http_req_duration{') && name !== 'http_req_duration') continue;
        const v = metric.values;
        lines.push(`${name.padEnd(60)} med=${(v.med || 0).toFixed(1)}ms p95=${(v['p(95)'] || 0).toFixed(1)}ms p99=${(v['p(99)'] || 0).toFixed(1)}ms`);
    }
    for (const name of ['http_reqs', 'http_req_failed', 'dropped_iterations', 'iterations', 'vus_max']) {
        if (data.metrics[name]) lines.push(`${name.padEnd(60)} ${JSON.stringify(data.metrics[name].values)}`);
    }
    return { [file]: JSON.stringify(data, null, 1), stdout: lines.join('\n') + '\n' };
}
