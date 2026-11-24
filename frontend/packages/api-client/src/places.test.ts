import { describe, expect, it, vi } from 'vitest';
import {
  createApiClient,
  listPlaces,
  type Place,
  type SessionTokens,
  type TokenStore,
} from './index';

const BASE_URL = 'https://api.test/api/v1';

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const LARIOS: Place = {
  displayName: '5, Calle Larios, Centro Histórico, Centro, Málaga, Andalucía, 29005, España',
  latitude: 36.7196,
  longitude: -4.4214,
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

function clientWith(fetch: (request: Request) => Promise<Response>) {
  return createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch: vi.fn(fetch) });
}

describe('listPlaces', () => {
  it('sends the text as q and returns the places', async () => {
    const client = clientWith(async (request) => {
      const url = new URL(request.url);
      expect(url.pathname).toBe('/api/v1/places');
      expect(url.searchParams.get('q')).toBe('Calle Larios 5 & Co #2');
      return json(200, [LARIOS]);
    });

    expect(await listPlaces(client, 'Calle Larios 5 & Co #2')).toEqual({
      ok: true,
      data: [LARIOS],
    });
  });

  it('reports an unavailable search with its code, so the screen says to place the pin by hand', async () => {
    const client = clientWith(async () =>
      json(503, { status: 503, code: 'GEOCODING_UNAVAILABLE' }, 'application/problem+json'),
    );

    expect(await listPlaces(client, 'Calle Larios')).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 503, code: 'GEOCODING_UNAVAILABLE' },
    });
  });

  it('reports a network failure when the API cannot be reached', async () => {
    const client = clientWith(async () => {
      throw new TypeError('Failed to fetch');
    });

    expect(await listPlaces(client, 'Calle Larios')).toEqual({
      ok: false,
      failure: { kind: 'network' },
    });
  });
});
