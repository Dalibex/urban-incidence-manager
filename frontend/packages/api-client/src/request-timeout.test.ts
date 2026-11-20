/**
 * Requests the API accepts and never answers, such as a paused container (#276 C): every call
 * through the client ends as a network failure after {@link RESPONSE_TIMEOUT_MS}, so a screen can
 * say that the server cannot be reached and keep its form.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createApiClient,
  createIncident,
  getCurrentUser,
  type SessionTokens,
  type TokenStore,
} from './index';
import type { components } from './schema';
import { createSessionFetch, RESPONSE_TIMEOUT_MS } from './session';

const BASE_URL = 'https://api.test/api/v1';

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const REPORT_KEY = '9b2d6f4e-1c3a-4e8b-a7d5-0f6e2c4b8a13';

const REPORT: components['schemas']['CreateIncidentRequest'] = {
  title: 'Street light off',
  description: 'It has been off for three days.',
  location: { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 },
};

const ME = { id: 'user-1', name: 'Lucía Martín', email: 'lucia@example.com', role: 'CITIZEN' };

function memoryStore(): TokenStore {
  let tokens: SessionTokens | null = TOKENS;
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

/** A `fetch` that never answers, not even when its request is aborted, like a runtime that ignores the signal. */
const NEVER = () => new Promise<Response>(() => undefined);

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function clientWith(fetch: (request: Request) => Promise<Response>) {
  return createApiClient(BASE_URL, { tokenStore: memoryStore(), fetch });
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('a request the API never answers (#276 C)', () => {
  it('ends as a network failure after the time limit, and aborts the request', async () => {
    const requests: Request[] = [];
    const client = clientWith((request) => {
      requests.push(request);
      return NEVER();
    });

    const result = getCurrentUser(client);
    await vi.advanceTimersByTimeAsync(RESPONSE_TIMEOUT_MS);

    expect(await result).toEqual({ ok: false, failure: { kind: 'network' } });
    expect(requests[0]?.signal.aborted).toBe(true);
  });

  it('ends a form as a network failure, so the screen keeps what was typed', async () => {
    const client = clientWith(NEVER);

    const result = createIncident(client, REPORT, REPORT_KEY);
    await vi.advanceTimersByTimeAsync(RESPONSE_TIMEOUT_MS);

    expect(await result).toEqual({ ok: false, failure: { kind: 'network' }, fieldErrors: [] });
  });

  it('waits the whole limit for an answer that comes late', async () => {
    const client = clientWith(
      () =>
        new Promise<Response>((resolve) => {
          setTimeout(() => resolve(json(200, ME)), RESPONSE_TIMEOUT_MS - 1);
        }),
    );

    const result = getCurrentUser(client);
    await vi.advanceTimersByTimeAsync(RESPONSE_TIMEOUT_MS);

    expect(await result).toEqual({ ok: true, data: ME });
  });

  it('limits the refresh after a 401 too', async () => {
    const paths: string[] = [];
    const client = clientWith((request) => {
      const path = new URL(request.url).pathname;
      paths.push(path);
      return path.endsWith('/sessions/refresh')
        ? NEVER()
        : Promise.resolve(json(401, { status: 401 }));
    });

    const result = getCurrentUser(client);
    await vi.advanceTimersByTimeAsync(RESPONSE_TIMEOUT_MS);

    // The refresh said nothing about the token: the caller sees the original 401.
    expect(await result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 401, code: undefined },
    });
    expect(paths).toEqual(['/api/v1/users/me', '/api/v1/sessions/refresh']);
  });

  it('clears its timer once the API answers', async () => {
    const client = clientWith(() => Promise.resolve(json(200, ME)));

    expect(await getCurrentUser(client)).toEqual({ ok: true, data: ME });
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('an abort asked by the caller (#276 C)', () => {
  function sessionFetchSeeing(received: Request[]) {
    return createSessionFetch(BASE_URL, {
      tokenStore: memoryStore(),
      fetch: async (request) => {
        received.push(request);
        return NEVER();
      },
    });
  }

  it('reaches fetch when the signal was already aborted', async () => {
    const received: Request[] = [];
    const controller = new AbortController();
    controller.abort();

    void sessionFetchSeeing(received)(
      new Request(`${BASE_URL}/users/me`, { signal: controller.signal }),
    ).catch(() => undefined);
    await vi.advanceTimersByTimeAsync(0);

    expect(received[0]?.signal.aborted).toBe(true);
  });

  it('reaches fetch when the signal is aborted on the way', async () => {
    const received: Request[] = [];
    const controller = new AbortController();

    void sessionFetchSeeing(received)(
      new Request(`${BASE_URL}/users/me`, { signal: controller.signal }),
    ).catch(() => undefined);
    await vi.advanceTimersByTimeAsync(0);
    expect(received[0]?.signal.aborted).toBe(false);

    controller.abort();
    expect(received[0]?.signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(RESPONSE_TIMEOUT_MS);
  });
});
