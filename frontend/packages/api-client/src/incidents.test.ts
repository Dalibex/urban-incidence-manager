import { describe, expect, it, vi } from 'vitest';
import {
  acceptIncident,
  assignIncident,
  closeIncident,
  createApiClient,
  createIncident,
  createIncidentComment,
  getIncident,
  listCategories,
  listDepartments,
  listIncidentClassificationChanges,
  listIncidentComments,
  listIncidentLocations,
  listIncidents,
  listTechnicians,
  rejectIncident,
  reopenIncident,
  requestIncidentInformation,
  resolveIncident,
  updateIncident,
  validateIncident,
  type ApiClient,
  type SessionTokens,
  type TokenStore,
} from './index';
import type { components } from './schema';

const BASE_URL = 'https://api.test/api/v1';

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

function memoryStore(initial: SessionTokens | null): TokenStore {
  let tokens = initial;
  return {
    load: async () => tokens,
    save: async (next) => {
      tokens = next;
    },
    clear: async () => {
      tokens = null;
    },
  };
}

function json(status: number, body: unknown, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

const BODY: components['schemas']['CreateIncidentRequest'] = {
  title: 'Street light off',
  description: 'It has been off for three days.',
  location: { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 },
};

const CREATED: components['schemas']['IncidentResponse'] = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'It has been off for three days.',
  status: 'REPORTED',
  category: null,
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: null,
    neighborhood: null,
    district: null,
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
};

describe('createIncident', () => {
  it('returns the created incident and sends the body with the access token', async () => {
    let authorization: string | null = null;
    let sent: unknown = undefined;
    const fetch = vi.fn(async (request: Request) => {
      authorization = request.headers.get('Authorization');
      sent = await request.json();
      return json(201, CREATED);
    });
    const client = createApiClient(BASE_URL, {
      tokenStore: memoryStore(TOKENS),
      fetch,
    });

    const result = await createIncident(client, BODY);

    expect(result).toEqual({ ok: true, data: CREATED });
    expect(authorization).toBe('Bearer access-1');
    expect(sent).toEqual(BODY);
  });

  it('carries the per-field errors of a 422 problem', async () => {
    const errors = [
      { field: 'location.latitude', message: 'must be between -90 and 90' },
      { field: 'categoryId', message: 'The category does not exist or is not active.' },
    ];
    const fetch = vi.fn(async (request: Request) => {
      expect(new URL(request.url).pathname).toBe('/api/v1/incidents');
      return json(422, {
        type: 'about:blank',
        title: 'Unprocessable Content',
        status: 422,
        errors,
      });
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await createIncident(client, BODY)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 422, code: undefined },
      fieldErrors: errors,
    });
  });

  it('reports a network failure when the API cannot be reached', async () => {
    const fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await createIncident(client, BODY)).toEqual({
      ok: false,
      failure: { kind: 'network' },
      fieldErrors: [],
    });
  });
});

describe('listCategories', () => {
  const CATEGORIES: components['schemas']['CategoryResponse'][] = [
    { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Lighting', description: null },
  ];

  it('returns the active categories', async () => {
    const fetch = vi.fn(async (request: Request) => {
      expect(new URL(request.url).pathname).toBe('/api/v1/categories');
      return json(200, CATEGORIES);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await listCategories(client)).toEqual({ ok: true, data: CATEGORIES });
  });

  it('reports a problem with its status so the screen can offer a retry', async () => {
    const fetch = vi.fn(async () =>
      json(500, { status: 500, code: 'INTERNAL_ERROR' }, 'application/problem+json'),
    );
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await listCategories(client)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 500, code: 'INTERNAL_ERROR' },
    });
  });

  it('reports a network failure when the API cannot be reached', async () => {
    const fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await listCategories(client)).toEqual({ ok: false, failure: { kind: 'network' } });
  });
});

describe('listDepartments', () => {
  it('returns the active departments', async () => {
    const departments: components['schemas']['DepartmentResponse'][] = [
      { id: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e', name: 'Street lighting' },
    ];
    const fetch = vi.fn(async (request: Request) => {
      expect(new URL(request.url).pathname).toBe('/api/v1/departments');
      return json(200, departments);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await listDepartments(client)).toEqual({ ok: true, data: departments });
  });
});

describe('listTechnicians', () => {
  it('returns the active technicians', async () => {
    const technicians: components['schemas']['TechnicianSummary'][] = [
      { id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', name: 'Sergio Molina' },
    ];
    const fetch = vi.fn(async (request: Request) => {
      expect(new URL(request.url).pathname).toBe('/api/v1/technicians');
      return json(200, technicians);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await listTechnicians(client)).toEqual({ ok: true, data: technicians });
  });
});

const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';

function clientAnswering(status: number, body: unknown) {
  const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => json(status, body));
  return { fetch, client: createApiClient(BASE_URL, { tokenStore: memoryStore(null), fetch }) };
}

describe('incident calls', () => {
  it('sends the page and repeats status once per value, as the contract expects', async () => {
    const { fetch, client } = clientAnswering(200, { content: [], page: {} });

    await listIncidents(client, { page: 2, size: 20, status: ['REPORTED', 'VALIDATED'] });

    const url = new URL(fetch.mock.calls[0]![0].url);
    expect(url.pathname).toBe('/api/v1/incidents');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.getAll('status')).toEqual(['REPORTED', 'VALIDATED']);
  });

  it('asks for one page of the classification changes of an incident', async () => {
    const page = { content: [], page: { number: 1, size: 100, totalElements: 100, totalPages: 1 } };
    const { fetch, client } = clientAnswering(200, page);

    const result = await listIncidentClassificationChanges(client, INCIDENT_ID, {
      page: 1,
      size: 100,
    });

    const url = new URL(fetch.mock.calls[0]![0].url);
    expect(url.pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}/classification-changes`);
    expect(url.searchParams.get('page')).toBe('1');
    expect(url.searchParams.get('size')).toBe('100');
    expect(result).toEqual({ ok: true, data: page });
  });

  it('asks for one page of the comments of an incident', async () => {
    const page = { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } };
    const { fetch, client } = clientAnswering(200, page);

    const result = await listIncidentComments(client, INCIDENT_ID, { page: 0, size: 100 });

    const url = new URL(fetch.mock.calls[0]![0].url);
    expect(url.pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}/comments`);
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.get('size')).toBe('100');
    expect(result).toEqual({ ok: true, data: page });
  });

  it('asks for the positions of the map with the same filters as the list', async () => {
    const { fetch, client } = clientAnswering(200, { content: [], truncated: false });

    const result = await listIncidentLocations(client, {
      status: ['REPORTED', 'ASSIGNED'],
      priority: ['HIGH'],
    });

    const url = new URL(fetch.mock.calls[0]![0].url);
    expect(url.pathname).toBe('/api/v1/incident-locations');
    expect(url.searchParams.getAll('status')).toEqual(['REPORTED', 'ASSIGNED']);
    expect(url.searchParams.getAll('priority')).toEqual(['HIGH']);
    expect(result).toEqual({ ok: true, data: { content: [], truncated: false } });
  });

  it('reports a missing incident as a 404 problem', async () => {
    const { fetch, client } = clientAnswering(404, { status: 404 });

    const result = await getIncident(client, INCIDENT_ID);

    expect(new URL(fetch.mock.calls[0]![0].url).pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}`);
    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 404, code: undefined },
    });
  });
});

describe('updateIncident', () => {
  const UPDATE: components['schemas']['UpdateIncidentRequest'] = {
    version: 3,
    priority: 'HIGH',
    priorityReason: 'The street is dark near a school.',
  };

  it('sends a PATCH with the body and returns the incident with its new version', async () => {
    let sent: unknown = undefined;
    const fetch = vi.fn(async (request: Request) => {
      expect(request.method).toBe('PATCH');
      expect(new URL(request.url).pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}`);
      sent = await request.json();
      return json(200, CREATED);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await updateIncident(client, INCIDENT_ID, UPDATE)).toEqual({ ok: true, data: CREATED });
    expect(sent).toEqual(UPDATE);
  });

  it('carries the code of a 409, so the screen can ask to reload', async () => {
    const { client } = clientAnswering(409, { status: 409, code: 'CONCURRENT_MODIFICATION' });

    expect(await updateIncident(client, INCIDENT_ID, UPDATE)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 409, code: 'CONCURRENT_MODIFICATION' },
      fieldErrors: [],
    });
  });

  it('carries the per-field errors of a 422 problem', async () => {
    const errors = [{ field: 'priorityReason', message: 'A priority needs its justification.' }];
    const { client } = clientAnswering(422, { status: 422, code: 'VALIDATION_FAILED', errors });

    expect(await updateIncident(client, INCIDENT_ID, UPDATE)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
      fieldErrors: errors,
    });
  });
});

const ACCEPTANCE = { version: 3 };
const RESOLUTION = { version: 3, reason: 'Replaced the lamp and checked it at night.' };
const VALIDATION = { version: 0 };
const REJECTION = { version: 0, reason: 'This streetlight belongs to a private estate.' };
const INFORMATION_REQUEST = { version: 0, reason: 'Which street is it on?' };
const CLOSURE = { version: 4 };
const REOPENING = { version: 4, reason: 'The lamp went off again the same night.' };
const ASSIGNMENT = {
  version: 2,
  departmentId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  technicianId: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
  team: 'Night shift',
};

describe.each([
  [
    'validateIncident',
    'validation',
    VALIDATION,
    (client: ApiClient) => validateIncident(client, INCIDENT_ID, VALIDATION),
  ],
  [
    'rejectIncident',
    'rejection',
    REJECTION,
    (client: ApiClient) => rejectIncident(client, INCIDENT_ID, REJECTION),
  ],
  [
    'requestIncidentInformation',
    'information-requests',
    INFORMATION_REQUEST,
    (client: ApiClient) => requestIncidentInformation(client, INCIDENT_ID, INFORMATION_REQUEST),
  ],
  [
    'assignIncident',
    'assignments',
    ASSIGNMENT,
    (client: ApiClient) => assignIncident(client, INCIDENT_ID, ASSIGNMENT),
  ],
  [
    'acceptIncident',
    'acceptance',
    ACCEPTANCE,
    (client: ApiClient) => acceptIncident(client, INCIDENT_ID, ACCEPTANCE),
  ],
  [
    'resolveIncident',
    'resolution',
    RESOLUTION,
    (client: ApiClient) => resolveIncident(client, INCIDENT_ID, RESOLUTION),
  ],
  [
    'closeIncident',
    'closure',
    CLOSURE,
    (client: ApiClient) => closeIncident(client, INCIDENT_ID, CLOSURE),
  ],
  [
    'reopenIncident',
    'reopening',
    REOPENING,
    (client: ApiClient) => reopenIncident(client, INCIDENT_ID, REOPENING),
  ],
] as const)('%s', (_name, resource, body, send) => {
  it(`sends a POST to /${resource} and returns the incident with its new version`, async () => {
    let sent: unknown = undefined;
    const fetch = vi.fn(async (request: Request) => {
      expect(request.method).toBe('POST');
      expect(new URL(request.url).pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}/${resource}`);
      sent = await request.json();
      return json(200, CREATED);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await send(client)).toEqual({ ok: true, data: CREATED });
    expect(sent).toEqual(body);
  });

  it('carries the code of a 409, so the screen can say why', async () => {
    const { client } = clientAnswering(409, { status: 409, code: 'INVALID_TRANSITION' });

    expect(await send(client)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 409, code: 'INVALID_TRANSITION' },
      fieldErrors: [],
    });
  });
});

describe('createIncidentComment', () => {
  const COMMENT: components['schemas']['CommentResponse'] = {
    id: 'f6a7b8c9-d0e1-4f2a-b3c4-d5e6f7a8b9c0',
    author: {
      id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
      name: 'Lucía Martín',
      role: 'MUNICIPAL_OPERATOR',
    },
    text: 'The access is through the back street.',
    createdAt: '2026-11-02T12:05:00Z',
  };

  it('sends the text to /comments and returns the created comment', async () => {
    let sent: unknown = undefined;
    const fetch = vi.fn(async (request: Request) => {
      expect(request.method).toBe('POST');
      expect(new URL(request.url).pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}/comments`);
      sent = await request.json();
      return json(201, COMMENT);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await createIncidentComment(client, INCIDENT_ID, { text: COMMENT.text })).toEqual({
      ok: true,
      data: COMMENT,
    });
    expect(sent).toEqual({ text: COMMENT.text });
  });

  it('carries the per-field errors of a 422 problem', async () => {
    const errors = [{ field: 'text', message: 'Write the comment.' }];
    const { client } = clientAnswering(422, { status: 422, code: 'VALIDATION_FAILED', errors });

    expect(await createIncidentComment(client, INCIDENT_ID, { text: ' ' })).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
      fieldErrors: errors,
    });
  });
});
