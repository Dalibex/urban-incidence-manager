import { describe, expect, it, vi } from 'vitest';
import { createApiClient, createIncident, type SessionTokens, type TokenStore } from './index';
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
