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

import { createApiClient, signIn, signOut, type SessionTokens, type TokenStore } from './index';

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
  let onSessionExpired: ReturnType<typeof vi.fn<() => void>>;

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

/**
 * #162: a refresh that fails without saying anything about the refresh token (no network, a `5xx`,
 * a `400` or a `422`) keeps the session, and the next `401` refreshes again with the same token
 * (#161). T2 and T7 without a previous failure are the tests of T4 above.
 */
describe('#162: the session recovers after a failed refresh', () => {
  let onSessionExpired: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(() => {
    onSessionExpired = vi.fn();
  });

  const networkDown: Handler = () => {
    throw new TypeError('Failed to fetch');
  };

  /** Each refresh gets the next failure, and then the rotating refresh of the contract. */
  function failingRefresh(...failures: Handler[]): Handler {
    const works = rotatingRefresh();
    let attempt = 0;
    return (request) => (failures[attempt++] ?? works)(request);
  }

  function clientFor(refresh: Handler) {
    const api = fakeApi({ 'GET /users/me': onlyFreshToken, 'POST /sessions/refresh': refresh });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });
    return { api, tokens, client };
  }

  const refreshTokensSent = (api: ReturnType<typeof fakeApi>) =>
    api
      .calls('POST', '/sessions/refresh')
      .map((request) => (JSON.parse(request.body) as { refreshToken: string }).refreshToken);

  it('T1: refreshes again with the same refresh token once the network is back', async () => {
    const { api, tokens, client } = clientFor(failingRefresh(networkDown));

    const first = await client.GET('/users/me');
    expect(first.response.status).toBe(401);
    expect(tokens.current()).toEqual(OLD_TOKENS);

    const second = await client.GET('/users/me');

    expect(second.response.status).toBe(200);
    expect(second.data).toEqual(CURRENT_USER);
    expect(refreshTokensSent(api)).toEqual([OLD_TOKENS.refreshToken, OLD_TOKENS.refreshToken]);
    expect(api.calls('GET', '/users/me').at(-1)?.authorization).toBe(
      `Bearer ${NEW_TOKENS.accessToken}`,
    );
    expect(tokens.current()).toEqual(NEW_TOKENS);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it.each([
    ['T3', 503],
    ['T3', 500],
    ['T11', 400],
    ['T11', 422],
  ])(
    '%s: keeps the tokens after a %i on the refresh and refreshes at the next 401',
    async (_case, status) => {
      const { api, tokens, client } = clientFor(failingRefresh(() => problem(status)));

      const first = await client.GET('/users/me');
      expect(first.response.status).toBe(401);
      expect(tokens.current()).toEqual(OLD_TOKENS);
      expect(tokens.store.clear).not.toHaveBeenCalled();

      const second = await client.GET('/users/me');

      expect(second.response.status).toBe(200);
      expect(refreshTokensSent(api)).toEqual([OLD_TOKENS.refreshToken, OLD_TOKENS.refreshToken]);
      expect(tokens.current()).toEqual(NEW_TOKENS);
      expect(onSessionExpired).not.toHaveBeenCalled();
    },
  );

  it('T4: gives the 401 at every request while there is no network, and keeps the session', async () => {
    const { api, tokens, client } = clientFor(networkDown);

    for (let attempt = 0; attempt < 3; attempt++) {
      const { response } = await client.GET('/users/me');
      expect(response.status).toBe(401);
    }

    // Each request tries one refresh and gives up; none of them is repeated.
    expect(refreshTokensSent(api)).toEqual([
      OLD_TOKENS.refreshToken,
      OLD_TOKENS.refreshToken,
      OLD_TOKENS.refreshToken,
    ]);
    expect(api.calls('GET', '/users/me')).toHaveLength(3);
    expect(tokens.current()).toEqual(OLD_TOKENS);
    expect(tokens.store.clear).not.toHaveBeenCalled();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T5: requests rejected while the refresh is failing share it and all get the 401', async () => {
    let dropNetwork = (): void => undefined;
    const inFlight = new Promise<Response>((_resolve, reject) => {
      dropNetwork = () => reject(new TypeError('Failed to fetch'));
    });
    const { api, tokens, client } = clientFor(failingRefresh(() => inFlight));

    const pending = Promise.all([
      client.GET('/users/me'),
      client.GET('/users/me'),
      client.GET('/users/me'),
    ]);
    // Every request has been rejected while the only refresh is still waiting for the network.
    await vi.waitFor(() => {
      expect(api.calls('GET', '/users/me')).toHaveLength(3);
      expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    });
    dropNetwork();
    const results = await pending;

    expect(results.map(({ response }) => response.status)).toEqual([401, 401, 401]);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    expect(api.calls('GET', '/users/me')).toHaveLength(3);
    expect(tokens.current()).toEqual(OLD_TOKENS);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T6: requests rejected after a failed refresh share one new refresh', async () => {
    const { api, tokens, client } = clientFor(failingRefresh(networkDown));
    await client.GET('/users/me');

    // The refresh token works once: one refresh per request would end the session.
    const results = await Promise.all([
      client.GET('/users/me'),
      client.GET('/users/me'),
      client.GET('/users/me'),
    ]);

    expect(results.map(({ response }) => response.status)).toEqual([200, 200, 200]);
    expect(refreshTokensSent(api)).toEqual([OLD_TOKENS.refreshToken, OLD_TOKENS.refreshToken]);
    expect(tokens.current()).toEqual(NEW_TOKENS);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T7: a refresh token rejected after a failed refresh ends the session once', async () => {
    const { api, tokens, client } = clientFor(
      failingRefresh(networkDown, () => problem(401, 'INVALID_REFRESH_TOKEN')),
    );
    await client.GET('/users/me');

    const results = await Promise.all([client.GET('/users/me'), client.GET('/users/me')]);
    const after = await client.GET('/users/me');

    expect(results.map(({ response }) => response.status)).toEqual([401, 401]);
    expect(after.response.status).toBe(401);
    // The spent refresh token is not sent again once the session has ended.
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(2);
    expect(api.calls('GET', '/users/me').at(-1)?.authorization).toBeNull();
    expect(tokens.current()).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('T8: ends the session when the new tokens cannot be saved', async () => {
    const { api, tokens, client } = clientFor(rotatingRefresh());
    vi.mocked(tokens.store.save).mockRejectedValueOnce(new Error('Secure store unavailable'));

    const { response } = await client.GET('/users/me');

    expect(response.status).toBe(401);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    expect(api.calls('GET', '/users/me')).toHaveLength(1);
    expect(tokens.store.clear).toHaveBeenCalledTimes(1);
    expect(tokens.current()).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('T12: a failed refresh of an old token does not forget a newer refresh of another one', async () => {
    // The store gets other tokens while the refresh of the old ones is in flight, for example
    // because the user signed in again. Their access token is also rejected and starts its own
    // refresh. Each refresh token works once.
    const SIGNED_IN: SessionTokens = {
      ...OLD_TOKENS,
      accessToken: 'signed-in-access',
      refreshToken: 'refresh-signed-in',
    };
    const RENEWED: SessionTokens = {
      ...NEW_TOKENS,
      accessToken: 'renewed-access',
      refreshToken: 'refresh-renewed',
    };
    let dropNetwork = (): void => undefined;
    let answerNewer = (): void => undefined;
    const olderRefresh = new Promise<Response>((_resolve, reject) => {
      dropNetwork = () => reject(new TypeError('Failed to fetch'));
    });
    const newerRefresh = new Promise<Response>((resolve) => {
      answerNewer = () => resolve(json(200, RENEWED));
    });
    const spent = new Set<string>();
    const api = fakeApi({
      'GET /users/me': (request) =>
        request.authorization === `Bearer ${RENEWED.accessToken}`
          ? json(200, CURRENT_USER)
          : problem(401),
      'POST /sessions/refresh': (request) => {
        const { refreshToken } = JSON.parse(request.body) as { refreshToken: string };
        if (spent.has(refreshToken)) {
          return problem(401, 'INVALID_REFRESH_TOKEN');
        }
        spent.add(refreshToken);
        return refreshToken === OLD_TOKENS.refreshToken ? olderRefresh : newerRefresh;
      },
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });

    const older = client.GET('/users/me');
    await vi.waitFor(() => expect(api.calls('POST', '/sessions/refresh')).toHaveLength(1));
    await tokens.store.save(SIGNED_IN);
    const newer = client.GET('/users/me');
    await vi.waitFor(() => expect(api.calls('POST', '/sessions/refresh')).toHaveLength(2));

    // The refresh of the old tokens fails while the newer one is still in flight.
    dropNetwork();
    expect((await older).response.status).toBe(401);

    // A request rejected with the signed-in token waits for the newer refresh, it does not start
    // a third one with the refresh token that refresh is already spending.
    const later = client.GET('/users/me');
    await vi.waitFor(() => expect(api.calls('GET', '/users/me')).toHaveLength(3));
    answerNewer();
    const results = await Promise.all([newer, later]);

    expect(results.map(({ response }) => response.status)).toEqual([200, 200]);
    expect(refreshTokensSent(api)).toEqual([OLD_TOKENS.refreshToken, SIGNED_IN.refreshToken]);
    expect(tokens.current()).toEqual(RENEWED);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});

/**
 * #238: a refresh that is still in flight when the user signs out must not bring the session back
 * (#237). The refresh starts because a request is rejected with the expired access token; the user
 * then signs out before the refresh answers.
 */
describe('#238: signing out leaves no session behind', () => {
  let onSessionExpired: ReturnType<typeof vi.fn<() => void>>;

  beforeEach(() => {
    onSessionExpired = vi.fn();
  });

  const networkDown: Handler = () => {
    throw new TypeError('Failed to fetch');
  };

  /** Revokes only the fresh session; the expired access token gets 401, as on the real API. */
  const revokeFreshSession: Handler = (request) =>
    request.authorization === `Bearer ${NEW_TOKENS.accessToken}`
      ? new Response(null, { status: 204 })
      : problem(401);

  /** A refresh that answers only when the test says so. */
  function refreshInFlight() {
    let answer: (response: Response) => void = () => undefined;
    const response = new Promise<Response>((resolve) => {
      answer = resolve;
    });
    return { handler: (() => response) satisfies Handler, answer };
  }

  function clientWith(deleteSession: Handler, extraRoutes: Record<string, Handler> = {}) {
    const refresh = refreshInFlight();
    const api = fakeApi({
      'GET /users/me': onlyFreshToken,
      'POST /sessions/refresh': refresh.handler,
      'DELETE /sessions/current': deleteSession,
      ...extraRoutes,
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });
    return { api, tokens, client, refresh };
  }

  /** A request rejected with the expired access token, whose refresh is now in flight. */
  // Wrapped in an object: an async function that returned the request itself would wait for it.
  async function startRefresh(setUp: ReturnType<typeof clientWith>) {
    const request = setUp.client.GET('/users/me');
    await vi.waitFor(() => expect(setUp.api.calls('POST', '/sessions/refresh')).toHaveLength(1));
    return { request };
  }

  async function expectNoTokenSent(setUp: ReturnType<typeof clientWith>) {
    const { response } = await setUp.client.GET('/users/me');
    expect(response.status).toBe(401);
    expect(setUp.api.calls('GET', '/users/me').at(-1)?.authorization).toBeNull();
    expect(setUp.api.calls('POST', '/sessions/refresh')).toHaveLength(1);
  }

  it('T1: a refresh that succeeds while signing out is used to revoke the session', async () => {
    const setUp = clientWith(revokeFreshSession);
    const { request } = await startRefresh(setUp);

    // The sign-out is rejected with the expired token too, so it waits for the same refresh.
    const signingOut = signOut(setUp.client, setUp.tokens.store);
    await vi.waitFor(() => expect(setUp.api.calls('DELETE', '/sessions/current')).toHaveLength(1));
    setUp.refresh.answer(json(200, NEW_TOKENS));
    await signingOut;
    await request;

    expect(setUp.api.calls('DELETE', '/sessions/current').map((r) => r.authorization)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
    ]);
    expect(setUp.tokens.current()).toBeNull();
    await expectNoTokenSent(setUp);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T2: a normal sign-out revokes the session and empties the store', async () => {
    const api = fakeApi({ 'DELETE /sessions/current': revokeFreshSession });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await signOut(client, tokens.store);

    expect(api.calls('DELETE', '/sessions/current')).toHaveLength(1);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(0);
    expect(tokens.current()).toBeNull();
  });

  it.each([
    ['without network', networkDown],
    ['that the server fails', (() => problem(503)) satisfies Handler],
  ])(
    'T3: a refresh that succeeds after a sign-out %s does not save its tokens',
    async (_, deleteSession) => {
      const setUp = clientWith(deleteSession);
      const { request } = await startRefresh(setUp);

      await signOut(setUp.client, setUp.tokens.store);
      setUp.refresh.answer(json(200, NEW_TOKENS));

      // The request is not repeated with the tokens of a session the user ended.
      expect((await request).response.status).toBe(401);
      expect(setUp.api.calls('GET', '/users/me')).toHaveLength(1);
      expect(setUp.tokens.current()).toBeNull();
      expect(setUp.tokens.store.save).not.toHaveBeenCalled();
      await expectNoTokenSent(setUp);
      expect(onSessionExpired).not.toHaveBeenCalled();
    },
  );

  it('T4: a refresh rejected after signing out does not report an expired session', async () => {
    const setUp = clientWith(networkDown);
    const { request } = await startRefresh(setUp);

    await signOut(setUp.client, setUp.tokens.store);
    setUp.refresh.answer(problem(401, 'INVALID_REFRESH_TOKEN'));

    expect((await request).response.status).toBe(401);
    expect(setUp.tokens.current()).toBeNull();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T4: a refresh rejected while the sign-out waits for it does not report an expired session', async () => {
    const setUp = clientWith(revokeFreshSession);
    const { request } = await startRefresh(setUp);

    const signingOut = signOut(setUp.client, setUp.tokens.store);
    await vi.waitFor(() => expect(setUp.api.calls('DELETE', '/sessions/current')).toHaveLength(1));
    setUp.refresh.answer(problem(401, 'INVALID_REFRESH_TOKEN'));
    await signingOut;
    await request;

    expect(setUp.tokens.current()).toBeNull();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('sends no refresh when the user signs out while the store is being read for it', async () => {
    const setUp = clientWith(networkDown);
    // The first read is the request's own; the second, the refresh's, answers only when told to.
    let finishRead: (tokens: SessionTokens) => void = () => undefined;
    const slowRead = new Promise<SessionTokens>((resolve) => {
      finishRead = resolve;
    });
    vi.mocked(setUp.tokens.store.load)
      .mockImplementationOnce(async () => OLD_TOKENS)
      .mockImplementationOnce(() => slowRead);
    const request = setUp.client.GET('/users/me');
    await vi.waitFor(() => expect(setUp.tokens.store.load).toHaveBeenCalledTimes(2));

    await signOut(setUp.client, setUp.tokens.store);
    // The read returns what the store held before the sign-out cleared it.
    finishRead(OLD_TOKENS);

    expect((await request).response.status).toBe(401);
    expect(setUp.api.calls('POST', '/sessions/refresh')).toHaveLength(0);
    expect(setUp.tokens.current()).toBeNull();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T5: a refresh that fails after signing out leaves the store empty', async () => {
    const setUp = clientWith(networkDown);
    const { request } = await startRefresh(setUp);

    await signOut(setUp.client, setUp.tokens.store);
    setUp.refresh.answer(problem(503));

    expect((await request).response.status).toBe(401);
    expect(setUp.tokens.current()).toBeNull();
    await expectNoTokenSent(setUp);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('T6: signing out twice in a row throws nothing and leaves the store empty', async () => {
    const api = fakeApi({ 'DELETE /sessions/current': revokeFreshSession });
    const tokens = memoryTokenStore(NEW_TOKENS);
    const client = createApiClient(BASE_URL, { tokenStore: tokens.store, fetch: api.fetch });

    await expect(signOut(client, tokens.store)).resolves.toBeUndefined();
    await expect(signOut(client, tokens.store)).resolves.toBeUndefined();

    // The second sign-out has no token to send, and a 401 without a token starts no refresh.
    expect(api.calls('DELETE', '/sessions/current').map((r) => r.authorization)).toEqual([
      `Bearer ${NEW_TOKENS.accessToken}`,
      null,
    ]);
    expect(api.calls('POST', '/sessions/refresh')).toHaveLength(0);
    expect(tokens.current()).toBeNull();
  });

  it('T7: an app that closes before the refresh answers starts with no session', async () => {
    const setUp = clientWith(networkDown);
    await startRefresh(setUp);

    await signOut(setUp.client, setUp.tokens.store);

    // The process ends with the refresh unanswered; the next start reads the same device store.
    const api = fakeApi({ 'GET /users/me': onlyFreshToken });
    const restarted = createApiClient(BASE_URL, {
      tokenStore: setUp.tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });
    const { response } = await restarted.GET('/users/me');

    expect(setUp.tokens.current()).toBeNull();
    expect(response.status).toBe(401);
    expect(api.calls('GET', '/users/me')[0]?.authorization).toBeNull();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  describe('signing in again before the refresh of the old session answers', () => {
    const SIGNED_IN: SessionTokens = {
      ...NEW_TOKENS,
      accessToken: 'signed-in-access',
      refreshToken: 'refresh-signed-in',
    };
    const credentials = { email: 'citizen@example.com', password: 'correct-password' };

    async function signOutAndInAgain(setUp: ReturnType<typeof clientWith>) {
      await signOut(setUp.client, setUp.tokens.store);
      const signedIn = await signIn(setUp.client, setUp.tokens.store, credentials);
      expect(signedIn.ok).toBe(true);
    }

    it('keeps the new session when the old refresh succeeds', async () => {
      const setUp = clientWith(networkDown, { 'POST /sessions': () => json(201, SIGNED_IN) });
      const { request } = await startRefresh(setUp);

      await signOutAndInAgain(setUp);
      setUp.refresh.answer(json(200, NEW_TOKENS));
      await request;

      expect(setUp.tokens.current()).toEqual(SIGNED_IN);
      expect(onSessionExpired).not.toHaveBeenCalled();
    });

    it('keeps the new session when the old refresh is rejected', async () => {
      const setUp = clientWith(networkDown, { 'POST /sessions': () => json(201, SIGNED_IN) });
      const { request } = await startRefresh(setUp);

      await signOutAndInAgain(setUp);
      setUp.refresh.answer(problem(401, 'INVALID_REFRESH_TOKEN'));
      await request;

      expect(setUp.tokens.current()).toEqual(SIGNED_IN);
      expect(onSessionExpired).not.toHaveBeenCalled();
    });
  });
});

/**
 * #260: a request is never repeated with a session other than the one it was sent with (#259). User
 * A's request reaches the API with A's expired access token, and the test holds its answer back
 * while A signs out and, in most cases, user B signs in on the same device.
 */
describe('#260: a request rejected after a sign-out never runs with another session', () => {
  const B_TOKENS: SessionTokens = {
    ...NEW_TOKENS,
    accessToken: 'b-access',
    refreshToken: 'b-refresh',
  };
  const B_CREDENTIALS = { email: 'b@example.com', password: 'b-password' };

  const networkDown: Handler = () => {
    throw new TypeError('Failed to fetch');
  };
  // How Expo's fetch rejects when the server does not answer in time (network-failure.test.ts).
  const timedOut: Handler = () => {
    throw new Error('fetch failed: The request timed out.');
  };
  const serverError: Handler = () => problem(503);
  const revoked: Handler = () => new Response(null, { status: 204 });

  /** An answer that the API sends only when the test says so. */
  function heldAnswer() {
    let answer: (response: Response) => void = () => undefined;
    const response = new Promise<Response>((resolve) => {
      answer = resolve;
    });
    return { response, answer };
  }

  /**
   * A's request is the first `GET /users/me`: the API holds its answer. Later ones with A's expired
   * token get 401 at once, and any other token is accepted, so the test sees which token A's request
   * was repeated with.
   */
  function setUp(routes: Record<string, Handler>) {
    const aRequest = heldAnswer();
    let aRequestReceived = false;
    const api = fakeApi({
      'GET /users/me': (request) => {
        if (request.authorization !== `Bearer ${OLD_TOKENS.accessToken}`) {
          return json(200, CURRENT_USER);
        }
        if (aRequestReceived) {
          return problem(401);
        }
        aRequestReceived = true;
        return aRequest.response;
      },
      'POST /sessions': () => json(201, B_TOKENS),
      ...routes,
    });
    const tokens = memoryTokenStore(OLD_TOKENS);
    const onSessionExpired = vi.fn();
    const client = createApiClient(BASE_URL, {
      tokenStore: tokens.store,
      fetch: api.fetch,
      onSessionExpired,
    });
    return { api, tokens, client, aRequest, onSessionExpired };
  }

  /** Starts A's request and waits until it has reached the API. */
  // Wrapped in an object: an async function that returned the request itself would wait for it.
  async function sendARequest(context: ReturnType<typeof setUp>) {
    const request = context.client.GET('/users/me');
    await vi.waitFor(() => expect(context.api.calls('GET', '/users/me')).toHaveLength(1));
    return { request };
  }

  async function signInAsB(context: ReturnType<typeof setUp>) {
    expect((await signIn(context.client, context.tokens.store, B_CREDENTIALS)).ok).toBe(true);
  }

  function aRequestTokens(context: ReturnType<typeof setUp>) {
    return context.api.calls('GET', '/users/me').map((request) => request.authorization);
  }

  it.each([
    // T1
    ['after the server revoked the session', revoked],
    // T3
    ['without network', networkDown],
    ['after the sign-out timed out', timedOut],
    ['after the server failed to revoke the session', serverError],
  ])(
    'T1, T3: is not repeated with the session of a user who signed in %s',
    async (_, deleteSession) => {
      const context = setUp({ 'DELETE /sessions/current': deleteSession });
      const { request } = await sendARequest(context);

      await signOut(context.client, context.tokens.store);
      await signInAsB(context);
      context.aRequest.answer(problem(401));

      expect((await request).response.status).toBe(401);
      expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
      expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(0);
      expect(context.tokens.current()).toEqual(B_TOKENS);
      expect(context.onSessionExpired).not.toHaveBeenCalled();
    },
  );

  // A's screens stay usable while the sign-out waits for the server, so A can still send a request.
  it("T1: a request sent while the sign-out is in flight is not repeated with B's session", async () => {
    const deleteAnswer = heldAnswer();
    const context = setUp({ 'DELETE /sessions/current': () => deleteAnswer.response });
    const signingOut = signOut(context.client, context.tokens.store);
    await vi.waitFor(() =>
      expect(context.api.calls('DELETE', '/sessions/current')).toHaveLength(1),
    );
    const { request } = await sendARequest(context);

    deleteAnswer.answer(new Response(null, { status: 204 }));
    await signingOut;
    await signInAsB(context);
    context.aRequest.answer(problem(401));

    expect((await request).response.status).toBe(401);
    expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
    expect(context.tokens.current()).toEqual(B_TOKENS);
  });

  // The session can also end without a sign-out: a disabled account, a password change, or a
  // refresh token used twice (#191, #165, #125).
  it("is not repeated with B's session when A's session ended because its refresh was rejected", async () => {
    const context = setUp({
      'POST /sessions/refresh': () => problem(401, 'INVALID_REFRESH_TOKEN'),
    });
    const { request } = await sendARequest(context);

    // Another request of A gets 401, its refresh is rejected, and the app asks to sign in again.
    expect((await context.client.GET('/users/me')).response.status).toBe(401);
    expect(context.onSessionExpired).toHaveBeenCalledTimes(1);
    await signInAsB(context);
    context.aRequest.answer(problem(401));

    expect((await request).response.status).toBe(401);
    expect(aRequestTokens(context)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${OLD_TOKENS.accessToken}`,
    ]);
    expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(1);
    expect(context.tokens.current()).toEqual(B_TOKENS);
  });

  it('T2: is repeated once with the token another request of the same session refreshed', async () => {
    const context = setUp({ 'POST /sessions/refresh': rotatingRefresh() });
    const { request } = await sendARequest(context);

    // Another request of A gets 401 first and refreshes the session.
    const other = await context.client.GET('/users/me');
    expect(other.response.status).toBe(200);
    context.aRequest.answer(problem(401));

    expect((await request).response.status).toBe(200);
    expect(aRequestTokens(context)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
    ]);
    expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(1);
  });

  /** The sign-out's `DELETE` gets 401 with A's expired token and refreshes A's session to revoke it. */
  const revokeWithFreshToken: Handler = (request) =>
    request.authorization === `Bearer ${NEW_TOKENS.accessToken}`
      ? new Response(null, { status: 204 })
      : problem(401);

  it("T4: is not repeated with the token the sign-out refreshed, nor with B's", async () => {
    const context = setUp({
      'DELETE /sessions/current': revokeWithFreshToken,
      'POST /sessions/refresh': rotatingRefresh(),
    });
    const { request } = await sendARequest(context);

    await signOut(context.client, context.tokens.store);
    await signInAsB(context);
    context.aRequest.answer(problem(401));

    expect((await request).response.status).toBe(401);
    expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
    // The sign-out itself is still repeated with the fresh token, which revokes the session (#237).
    expect(context.api.calls('DELETE', '/sessions/current').map((r) => r.authorization)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
    ]);
  });

  it('T4: is not repeated with the token the sign-out is still refreshing', async () => {
    const refresh = heldAnswer();
    const context = setUp({
      'DELETE /sessions/current': revokeWithFreshToken,
      'POST /sessions/refresh': () => refresh.response,
    });
    const { request } = await sendARequest(context);

    const signingOut = signOut(context.client, context.tokens.store);
    await vi.waitFor(() => expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(1));
    // A's answer arrives while the sign-out waits for the refresh it needs to revoke the session.
    context.aRequest.answer(problem(401));
    expect((await request).response.status).toBe(401);
    refresh.answer(json(200, NEW_TOKENS));
    await signingOut;

    expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
    expect(context.api.calls('DELETE', '/sessions/current').map((r) => r.authorization)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
    ]);
    expect(context.tokens.current()).toBeNull();
    expect(context.onSessionExpired).not.toHaveBeenCalled();
  });

  it('T4: is not repeated with the refresh it was waiting for when the user signs out meanwhile', async () => {
    const refresh = heldAnswer();
    const context = setUp({
      'DELETE /sessions/current': revokeWithFreshToken,
      'POST /sessions/refresh': () => refresh.response,
    });
    const { request } = await sendARequest(context);
    // A's request is rejected first, and its refresh is in flight when A signs out.
    context.aRequest.answer(problem(401));
    await vi.waitFor(() => expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(1));

    const signingOut = signOut(context.client, context.tokens.store);
    await vi.waitFor(() =>
      expect(context.api.calls('DELETE', '/sessions/current')).toHaveLength(1),
    );
    refresh.answer(json(200, NEW_TOKENS));
    await signingOut;

    expect((await request).response.status).toBe(401);
    expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
    // The sign-out shares that refresh, and revokes the session with it.
    expect(context.api.calls('DELETE', '/sessions/current').map((r) => r.authorization)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
    ]);
    expect(context.tokens.current()).toBeNull();
  });

  it('T4: a request sent while the sign-out refreshes is not repeated with the refreshed token', async () => {
    const refresh = heldAnswer();
    const context = setUp({
      'DELETE /sessions/current': revokeWithFreshToken,
      'POST /sessions/refresh': () => refresh.response,
    });
    const signingOut = signOut(context.client, context.tokens.store);
    await vi.waitFor(() => expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(1));
    const { request } = await sendARequest(context);

    refresh.answer(json(200, NEW_TOKENS));
    await signingOut;
    context.aRequest.answer(problem(401));

    expect((await request).response.status).toBe(401);
    expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
    // The sign-out's own `DELETE` is still repeated with the fresh token, which revokes the session.
    expect(context.api.calls('DELETE', '/sessions/current').map((r) => r.authorization)).toEqual([
      `Bearer ${OLD_TOKENS.accessToken}`,
      `Bearer ${NEW_TOKENS.accessToken}`,
    ]);
    expect(context.tokens.current()).toBeNull();
  });

  it('T5: gives the 401 and sends nothing again when nobody signs in', async () => {
    const context = setUp({ 'DELETE /sessions/current': revoked });
    const { request } = await sendARequest(context);

    await signOut(context.client, context.tokens.store);
    context.aRequest.answer(problem(401));

    expect((await request).response.status).toBe(401);
    expect(aRequestTokens(context)).toEqual([`Bearer ${OLD_TOKENS.accessToken}`]);
    expect(context.api.calls('POST', '/sessions/refresh')).toHaveLength(0);
    expect(context.tokens.current()).toBeNull();
  });
});
