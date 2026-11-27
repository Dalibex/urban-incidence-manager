import { describe, expect, it, vi } from 'vitest';

import {
  createApiClient,
  deletePushToken,
  getNotificationSettings,
  registerPushToken,
  updateNotificationSettings,
  type SessionTokens,
  type TokenStore,
} from './index';

const BASE_URL = 'https://api.test/api/v1';
const EXPO_TOKEN = 'ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]';

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

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function problem(status: number): Response {
  return new Response(JSON.stringify({ type: 'about:blank', status }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

const noContent = () => new Response(null, { status: 204 });

function clientAnswering(answer: () => Response, store: TokenStore = memoryStore(TOKENS)) {
  const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => answer());
  return { fetch, client: createApiClient(BASE_URL, { tokenStore: store, fetch }) };
}

function firstRequest(fetch: ReturnType<typeof vi.fn>): Request {
  const call = fetch.mock.calls[0];
  if (!call) {
    throw new Error('No request was sent.');
  }
  return call[0] as Request;
}

function urlsOf(fetch: ReturnType<typeof vi.fn>): string[] {
  return fetch.mock.calls.map(([request]) => (request as Request).url);
}

describe('getNotificationSettings', () => {
  it('reads the settings of the signed-in citizen', async () => {
    // Arrange
    const { fetch, client } = clientAnswering(() => json(200, { push: false, email: true }));

    // Act
    const result = await getNotificationSettings(client);

    // Assert
    const request = firstRequest(fetch);
    expect(request.method).toBe('GET');
    expect(request.url).toBe(`${BASE_URL}/users/me/notification-settings`);
    expect(result).toEqual({ ok: true, data: { push: false, email: true } });
  });
});

describe('updateNotificationSettings', () => {
  it('patches only the channels it is given', async () => {
    // Arrange
    const { fetch, client } = clientAnswering(() => json(200, { push: true, email: true }));

    // Act
    const result = await updateNotificationSettings(client, { push: true });

    // Assert
    const request = firstRequest(fetch);
    expect(request.method).toBe('PATCH');
    expect(request.url).toBe(`${BASE_URL}/users/me/notification-settings`);
    expect(await request.json()).toEqual({ push: true });
    expect(result).toEqual({ ok: true, data: { push: true, email: true } });
  });

  it('reports a failed patch as a problem', async () => {
    // Arrange
    const { client } = clientAnswering(() => problem(422));

    // Act
    const result = await updateNotificationSettings(client, { email: false });

    // Assert
    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 422, code: undefined },
    });
  });
});

describe('registerPushToken', () => {
  it('posts the token in the body, never in the path', async () => {
    // Arrange
    const { fetch, client } = clientAnswering(noContent);

    // Act
    const result = await registerPushToken(client, EXPO_TOKEN);

    // Assert
    const request = firstRequest(fetch);
    expect(request.method).toBe('POST');
    expect(request.url).toBe(`${BASE_URL}/push-tokens`);
    expect(await request.json()).toEqual({ token: EXPO_TOKEN });
    expect(result.ok).toBe(true);
  });

  it('refreshes the session once on a 401 and repeats the registration with the new access token', async () => {
    // Arrange
    const refreshed: SessionTokens = {
      ...TOKENS,
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
    };
    const answers = [() => problem(401), () => json(200, refreshed), noContent];
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => {
      const answer = answers.shift();
      if (!answer) {
        throw new Error('Unexpected request');
      }
      return answer();
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    // Act
    const result = await registerPushToken(client, EXPO_TOKEN);

    // Assert
    expect(urlsOf(fetch)).toEqual([
      `${BASE_URL}/push-tokens`,
      `${BASE_URL}/sessions/refresh`,
      `${BASE_URL}/push-tokens`,
    ]);
    expect((fetch.mock.calls[2]?.[0] as Request).headers.get('Authorization')).toBe(
      'Bearer access-2',
    );
    expect(result.ok).toBe(true);
  });
});

describe('deletePushToken', () => {
  it('posts the token to the deletions resource with the stored access token', async () => {
    // Arrange
    const { fetch, client } = clientAnswering(noContent);

    // Act
    const result = await deletePushToken(client, EXPO_TOKEN);

    // Assert
    const request = firstRequest(fetch);
    expect(request.method).toBe('POST');
    expect(request.url).toBe(`${BASE_URL}/push-token-deletions`);
    expect(request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(await request.json()).toEqual({ token: EXPO_TOKEN });
    expect(result.ok).toBe(true);
  });

  it('sends the access token it is given even when the token store is empty (F2)', async () => {
    // Arrange
    const { fetch, client } = clientAnswering(noContent, memoryStore(null));

    // Act
    const result = await deletePushToken(client, EXPO_TOKEN, 'access-before-sign-out');

    // Assert
    const request = firstRequest(fetch);
    expect(request.url).toBe(`${BASE_URL}/push-token-deletions`);
    expect(request.headers.get('Authorization')).toBe('Bearer access-before-sign-out');
    expect(result.ok).toBe(true);
  });

  it('reports a network failure instead of throwing', async () => {
    // Arrange
    const fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(null), fetch });

    // Act
    const result = await deletePushToken(client, EXPO_TOKEN, 'access-before-sign-out');

    // Assert
    expect(result).toEqual({ ok: false, failure: { kind: 'network' } });
  });
});
