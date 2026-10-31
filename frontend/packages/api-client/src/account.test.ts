import { describe, expect, it, vi } from 'vitest';

import {
  callApi,
  createApiClient,
  getCurrentUser,
  signIn,
  type SessionTokens,
  type TokenStore,
} from './index';
import { createSessionFetch } from './session';

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

describe('callApi results', () => {
  it('reports the problem code so clients never read the message text', async () => {
    const fetch = vi.fn(async () =>
      json(
        401,
        { type: 'about:blank', status: 401, code: 'INVALID_CREDENTIALS' },
        'application/problem+json',
      ),
    );
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(null), fetch });

    const result = await signIn(client, memoryStore(null), { email: 'a@b.es', password: 'x' });

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 401, code: 'INVALID_CREDENTIALS' },
    });
  });

  it('reports a network failure when the API cannot be reached', async () => {
    const fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await getCurrentUser(client)).toEqual({ ok: false, failure: { kind: 'network' } });
  });

  it('stores the tokens of a successful sign-in', async () => {
    const store = memoryStore(null);
    const fetch = vi.fn(async () => json(201, TOKENS));
    const client = createApiClient(BASE_URL, { tokenStore: store, fetch });

    expect(await signIn(client, store, { email: 'a@b.es', password: 'secret-1' })).toEqual({
      ok: true,
      data: undefined,
    });
    expect(await store.load()).toEqual(TOKENS);
  });
});

describe('session refresh', () => {
  it('reuses tokens refreshed by another request instead of refreshing again', async () => {
    // The request was sent with access-0, but another request already refreshed to access-1.
    const store = memoryStore(TOKENS);
    const fetch = vi.fn(async (request: Request) => {
      if (request.url.endsWith('/sessions/refresh')) {
        return json(401, { type: 'about:blank', status: 401 });
      }
      const fresh = request.headers.get('Authorization') === `Bearer ${TOKENS.accessToken}`;
      if (!fresh) {
        await store.save(TOKENS);
      }
      return fresh ? json(200, { id: '1' }) : json(401, { type: 'about:blank', status: 401 });
    });
    const client = createApiClient(BASE_URL, { tokenStore: store, fetch });
    await store.save({ ...TOKENS, accessToken: 'access-0' });

    const result = await getCurrentUser(client);

    expect(result.ok).toBe(true);
    expect(fetch.mock.calls.some(([request]) => request.url.endsWith('/sessions/refresh'))).toBe(
      false,
    );
  });

  it('refreshes once even when the store saves the new tokens slowly', async () => {
    // A native store answers asynchronously. Timeline (ms): both requests read the old tokens
    // (0-50) and are sent; A is rejected at 50, refreshes at 100 and saves until 200. B is rejected
    // at 170 and reads the store, which still has the old tokens, until 220: after A's refresh ended.
    const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
    let tokens: SessionTokens | null = { ...TOKENS, accessToken: 'access-0' };
    const slowStore: TokenStore = {
      load: async () => {
        const snapshot = tokens;
        await wait(50);
        return snapshot;
      },
      save: async (next) => {
        await wait(100);
        tokens = next;
      },
      clear: async () => {
        tokens = null;
      },
    };
    let refreshes = 0;
    let rejections = 0;
    const fetch = vi.fn(async (request: Request) => {
      if (request.url.endsWith('/sessions/refresh')) {
        refreshes += 1;
        return refreshes === 1 ? json(200, TOKENS) : json(401, { status: 401 });
      }
      if (request.headers.get('Authorization') === `Bearer ${TOKENS.accessToken}`) {
        return json(200, { id: '1' });
      }
      rejections += 1;
      await wait(rejections === 1 ? 0 : 120);
      return json(401, { status: 401 });
    });
    const onSessionExpired = vi.fn<() => void>();
    const client = createApiClient(BASE_URL, { tokenStore: slowStore, fetch, onSessionExpired });

    const results = await Promise.all([getCurrentUser(client), getCurrentUser(client)]);

    expect(results.every((result) => result.ok)).toBe(true);
    expect(refreshes).toBe(1);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('keeps the tokens when the refresh cannot reach the API', async () => {
    const store = memoryStore(TOKENS);
    const fetch = vi.fn(async (request: Request) => {
      if (request.url.endsWith('/sessions/refresh')) {
        throw new TypeError('Failed to fetch');
      }
      return json(401, { status: 401 });
    });
    const onSessionExpired = vi.fn<() => void>();
    const client = createApiClient(BASE_URL, { tokenStore: store, fetch, onSessionExpired });

    const result = await getCurrentUser(client);

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 401, code: undefined },
    });
    expect(await store.load()).toEqual(TOKENS);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('ends the session when the new tokens of a refresh cannot be read', async () => {
    const store = memoryStore(TOKENS);
    const fetch = vi.fn(async (request: Request) =>
      request.url.endsWith('/sessions/refresh')
        ? new Response('<html>', { status: 200, headers: { 'Content-Type': 'text/html' } })
        : json(401, { status: 401 }),
    );
    const onSessionExpired = vi.fn<() => void>();
    const client = createApiClient(BASE_URL, { tokenStore: store, fetch, onSessionExpired });

    await getCurrentUser(client);

    expect(await store.load()).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it.each(['/api/v1/', '/api/v1///'])('works with the relative base URL %s', async (baseUrl) => {
    const store = memoryStore(TOKENS);
    const urls: string[] = [];
    const sessionFetch = createSessionFetch(baseUrl, {
      tokenStore: store,
      fetch: async (request) => {
        urls.push(new URL(request.url).pathname);
        return json(401, { status: 401, code: 'INVALID_CREDENTIALS' });
      },
    });

    // In the browser openapi-fetch hands over absolute URLs on the page's origin.
    await sessionFetch(new Request('http://localhost:5173/api/v1/sessions', { method: 'POST' }));
    await sessionFetch(new Request('http://localhost:5173/api/v1/users/me'));

    expect(urls).toEqual(['/api/v1/sessions', '/api/v1/users/me', '/api/v1/sessions/refresh']);
  });
});

describe('callApi', () => {
  it('treats a 204 without body as success', async () => {
    const fetch = vi.fn(async () => new Response(null, { status: 204 }));
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await callApi(() => client.DELETE('/sessions/current'))).toEqual({
      ok: true,
      data: undefined,
    });
  });
});
