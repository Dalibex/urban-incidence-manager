/**
 * Acceptance tests of the session handling shared by the web and mobile apps (#62, T4 and T6),
 * written before the feature (#39) as the testing lifecycle of CONTRIBUTING.md asks.
 *
 * They follow the contract (docs/api/openapi.yml): `refreshSession` rotates the refresh token and
 * a client must not refresh again when it answers 401; `deleteCurrentSession` is idempotent, and
 * with an expired access token the client refreshes first and then signs out.
 *
 * Assumed interface, to agree with the developer of #39 (ADR-0007 puts token refresh in this
 * package and platform storage behind small interfaces):
 * - `TokenStore`: `load()`, `save(tokens)` and `clear()`, implemented by each app with its own
 *   storage (secure storage on mobile).
 * - `createApiClient(baseUrl, { tokenStore, fetch, onSessionExpired })`: sends the access token,
 *   refreshes once on 401 and repeats the request once.
 * - `signOut(client, tokenStore)`: signs out on the server and always discards the tokens.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createApiClient, signOut, type SessionTokens, type TokenStore } from './index';

const BASE_URL = 'https://api.test/api/v1';

const OLD_TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'expired-access',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const NEW_TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'fresh-access',
  accessTokenExpiresAt: '2026-11-02T10:30:00Z',
  refreshToken: 'refresh-2',
  refreshTokenExpiresAt: '2026-11-16T10:15:00Z',
};

const CURRENT_USER = { id: '7c2e9a4b-1f3d-4e8a-9b6c-2d5f8e1a3b7c', role: 'CITIZEN' };

interface RecordedRequest {
  method: string;
  path: string;
  authorization: string | null;
  body: string;
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>;

/** Fake API: answers by "METHOD /path" and records every request it receives, in order. */
function fakeApi(routes: Record<string, Handler>) {
  const requests: RecordedRequest[] = [];
  const fetch = vi.fn(async (input: Request | string | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    const recorded: RecordedRequest = {
      method: request.method,
      path: new URL(request.url).pathname.replace('/api/v1', ''),
      authorization: request.headers.get('Authorization'),
      body: await request.text(),
    };
    requests.push(recorded);
    const handler = routes[`${recorded.method} ${recorded.path}`];
    if (!handler) {
      throw new Error(`Unexpected request ${recorded.method} ${recorded.path}`);
    }
    return handler(recorded);
  });
  const calls = (method: string, path: string) =>
    requests.filter((request) => request.method === method && request.path === path);
  return { fetch, requests, calls };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function problem(status: number, code?: string): Response {
  return new Response(JSON.stringify({ type: 'about:blank', status, code }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/** Accepts only the fresh access token, like the API after the old one has expired. */
const onlyFreshToken: Handler = (request) =>
  request.authorization === `Bearer ${NEW_TOKENS.accessToken}`
    ? json(200, CURRENT_USER)
    : problem(401);

/** Rotates the refresh token: refresh-1 works once, as the contract says. */
function rotatingRefresh(): Handler {
  let used = false;
  return (request) => {
    const { refreshToken } = JSON.parse(request.body) as { refreshToken: string };
    if (refreshToken !== OLD_TOKENS.refreshToken || used) {
      return problem(401, 'INVALID_REFRESH_TOKEN');
    }
    used = true;
    return json(200, NEW_TOKENS);
  };
}

function memoryTokenStore(initial: SessionTokens | null) {
  let tokens = initial;
  const store: TokenStore = {
    load: vi.fn(async () => tokens),
    save: vi.fn(async (next: SessionTokens) => {
      tokens = next;
    }),
    clear: vi.fn(async () => {
      tokens = null;
    }),
  };
  return { store, current: () => tokens };
}

describe('T4: transparent refresh', () => {
  let onSessionExpired: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onSessionExpired = vi.fn();
  });

  it('sends the stored access token as a bearer token', async () => {
    const api = fakeApi({ 'GET /users/me': () => json(200, CURRENT_USER) });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    const { data } = await client.GET('/users/me');

    expect(data).toEqual(CURRENT_USER);
    expect(api.requests[0]?.authorization).toBe(`Bearer ${NEW_TOKENS.accessToken}`);
  });

  it('refreshes an expired access token and repeats the request once with the new one', async () => {
    const api = fakeApi({
      'GET /users/me': onlyFreshToken,
      'POST /sessions/refresh': rotatingRefresh(),
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });

    const { data, response } = await client.GET('/users/me');

    expect(response.status).toBe(200);
    expect(data).toEqual(CURRENT_USER);
    expect(api.requests.map((request) => `${request.method} ${request.path}`)).toEqual([
      'GET /users/me',
      'POST /sessions/refresh',
      'GET /users/me',
    ]);
    expect(JSON.parse(api.calls('POST', '/sessions/refresh')[0]?.body ?? '')).toEqual({
      refreshToken: OLD_TOKENS.refreshToken,
    });
    expect(api.calls('GET', '/users/me')[1]?.authorization).toBe(
      `Bearer ${NEW_TOKENS.accessToken}`,
    );
    expect(tokens.current()).toEqual(NEW_TOKENS);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('repeats a request with a body with the same method and body', async () => {
    const body = { title: 'Broken streetlight', description: 'Off since Monday.' };
    const api = fakeApi({
      'POST /incidents': (request) =>
        request.authorization === `Bearer ${NEW_TOKENS.accessToken}`
          ? json(201, { id: 'f3a1', echo: JSON.parse(request.body) })
          : problem(401),
      'POST /sessions/refresh': rotatingRefresh(),
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    // The body is not a full CreateIncidentRequest: the fake API only echoes it.
    const { response } = await client.POST('/incidents', { body: body as never });

    expect(response.status).toBe(201);
    const posts = api.calls('POST', '/incidents');
    expect(posts).toHaveLength(2);
    expect(JSON.parse(posts[1]?.body ?? '')).toEqual(body);
  });

  it('repeats the request only once when the new token is also rejected', async () => {
    const api = fakeApi({
      'GET /users/me': () => problem(401),
      'POST /sessions/refresh': rotatingRefresh(),
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    const { response } = await client.GET('/users/me');

    expect(response.status).toBe(401);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    expect(api.calls('GET', '/users/me')).toHaveLength(2);
  });

  it('shares one refresh between requests that fail at the same time', async () => {
    const api = fakeApi({
      'GET /users/me': onlyFreshToken,
      'POST /sessions/refresh': rotatingRefresh(),
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });

    // The refresh token works once: a second refresh would fail and sign the user out.
    const results = await Promise.all([
      client.GET('/users/me'),
      client.GET('/users/me'),
      client.GET('/users/me'),
    ]);

    expect(results.map(({ response }) => response.status)).toEqual([200, 200, 200]);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('ends the session when the refresh token is rejected, without trying again', async () => {
    const api = fakeApi({
      'GET /users/me': () => problem(401),
      'POST /sessions/refresh': () => problem(401, 'INVALID_REFRESH_TOKEN'),
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });

    const { response } = await client.GET('/users/me');

    expect(response.status).toBe(401);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    expect(api.calls('GET', '/users/me')).toHaveLength(1);
    expect(tokens.current()).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('does not refresh when signing in with a wrong password', async () => {
    const api = fakeApi({ 'POST /sessions': () => problem(401, 'INVALID_CREDENTIALS') });
    const tokens = memoryTokenStore(null);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });

    const { response } = await client.POST('/sessions', {
      body: { email: 'ana@example.com', password: 'wrong-password' },
    });

    expect(response.status).toBe(401);
    expect(api.requests).toHaveLength(1);
    expect(api.requests[0]?.authorization).toBeNull();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('sends no token and does not refresh when there is no session', async () => {
    const api = fakeApi({ 'GET /users/me': () => problem(401) });
    const tokens = memoryTokenStore(null);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    const { response } = await client.GET('/users/me');

    expect(response.status).toBe(401);
    expect(api.requests).toHaveLength(1);
    expect(api.requests[0]?.authorization).toBeNull();
  });
});

describe('T6: sign-out', () => {
  it('revokes the session on the server and discards both tokens', async () => {
    const api = fakeApi({ 'DELETE /sessions/current': () => new Response(null, { status: 204 }) });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await signOut(client, tokens.store);

    expect(api.calls('DELETE', '/sessions/current')).toHaveLength(1);
    expect(api.calls('DELETE', '/sessions/current')[0]?.authorization).toBe(
      `Bearer ${NEW_TOKENS.accessToken}`,
    );
    expect(tokens.current()).toBeNull();
  });

  it('refreshes first when the access token has expired, so the refresh token is revoked', async () => {
    const api = fakeApi({
      'DELETE /sessions/current': (request) =>
        request.authorization === `Bearer ${NEW_TOKENS.accessToken}`
          ? new Response(null, { status: 204 })
          : problem(401),
      'POST /sessions/refresh': rotatingRefresh(),
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await signOut(client, tokens.store);

    expect(api.requests.map((request) => `${request.method} ${request.path}`)).toEqual([
      'DELETE /sessions/current',
      'POST /sessions/refresh',
      'DELETE /sessions/current',
    ]);
    expect(tokens.current()).toBeNull();
  });

  it('discards the tokens even when the server cannot be reached', async () => {
    const api = fakeApi({
      'DELETE /sessions/current': () => {
        throw new TypeError('Failed to fetch');
      },
    });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await expect(signOut(client, tokens.store)).resolves.toBeUndefined();

    expect(tokens.current()).toBeNull();
  });

  it('discards the tokens even when the server answers with an error', async () => {
    const api = fakeApi({ 'DELETE /sessions/current': () => problem(500) });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await signOut(client, tokens.store);

    expect(tokens.current()).toBeNull();
  });

  it('sends no token on the next request: protected screens need sign-in again', async () => {
    const api = fakeApi({
      'DELETE /sessions/current': () => new Response(null, { status: 204 }),
      'GET /users/me': (request) =>
        request.authorization ? json(200, CURRENT_USER) : problem(401),
    });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await signOut(client, tokens.store);
    const { response } = await client.GET('/users/me');

    expect(response.status).toBe(401);
    expect(api.calls('GET', '/users/me')[0]?.authorization).toBeNull();
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(0);
  });
});
