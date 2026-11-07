import { describe, expect, it, vi } from 'vitest';
import {
  createApiClient,
  createIncident,
  getIncident,
  listCategories,
  listIncidents,
  updateIncident,
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
