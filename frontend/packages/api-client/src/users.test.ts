import { describe, expect, it, vi } from 'vitest';
import {
  createApiClient,
  createUser,
  listUsers,
  updateUser,
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

const USER_ID = '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c';

const USER: components['schemas']['UserResponse'] = {
  id: USER_ID,
  name: 'Pedro Gómez',
  email: 'pedro.gomez@example.com',
  role: 'TECHNICIAN',
  enabled: true,
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
  const contentType = status >= 400 ? 'application/problem+json' : 'application/json';
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

function clientAnswering(status: number, body: unknown) {
  const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => json(status, body));
  return { fetch, client: createApiClient(BASE_URL, { tokenStore: memoryStore(null), fetch }) };
}

describe('listUsers', () => {
  it('sends the page and the filters, repeating role once per value', async () => {
    const page = {
      content: [USER],
      page: { number: 1, size: 20, totalElements: 21, totalPages: 2 },
    };
    const { fetch, client } = clientAnswering(200, page);

    const result = await listUsers(client, {
      page: 1,
      size: 20,
      name: 'ped',
      email: 'example',
      role: ['TECHNICIAN', 'ANALYST'],
    });

    const url = new URL(fetch.mock.calls[0]![0].url);
    expect(url.pathname).toBe('/api/v1/users');
    expect(url.searchParams.get('page')).toBe('1');
    expect(url.searchParams.get('name')).toBe('ped');
    expect(url.searchParams.get('email')).toBe('example');
    expect(url.searchParams.getAll('role')).toEqual(['TECHNICIAN', 'ANALYST']);
    expect(result).toEqual({ ok: true, data: page });
  });

  it('reports a refusal as a failure', async () => {
    const { client } = clientAnswering(403, { status: 403 });

    expect(await listUsers(client)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 403, code: undefined },
    });
  });
});

describe('createUser', () => {
  const BODY: components['schemas']['CreateUserRequest'] = {
    name: 'Pedro Gómez',
    email: 'pedro.gomez@example.com',
    password: 'correct-horse-battery',
    role: 'TECHNICIAN',
  };

  it('posts the account and returns the created user', async () => {
    let sent: unknown = undefined;
    const fetch = vi.fn(async (request: Request) => {
      expect(request.method).toBe('POST');
      expect(new URL(request.url).pathname).toBe('/api/v1/users');
      sent = await request.json();
      return json(201, USER);
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    expect(await createUser(client, BODY)).toEqual({ ok: true, data: USER });
    expect(sent).toEqual(BODY);
  });

  it('carries the code of a 409, so the screen can mark the email', async () => {
    const { client } = clientAnswering(409, { status: 409, code: 'EMAIL_ALREADY_EXISTS' });

    expect(await createUser(client, BODY)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 409, code: 'EMAIL_ALREADY_EXISTS' },
      fieldErrors: [],
    });
  });

  it('carries the per-field errors of a 422 problem', async () => {
    const errors = [{ field: 'password', message: 'Use at least 8 characters.' }];
    const { client } = clientAnswering(422, { status: 422, code: 'VALIDATION_FAILED', errors });

    expect(await createUser(client, BODY)).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
      fieldErrors: errors,
    });
  });

  it('reports an API that cannot be reached', async () => {
    const fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(null), fetch });

    expect(await createUser(client, BODY)).toEqual({
      ok: false,
      failure: { kind: 'network' },
      fieldErrors: [],
    });
  });
});

describe('updateUser', () => {
  it('patches only the properties it is given', async () => {
    let sent: unknown = undefined;
    const fetch = vi.fn(async (request: Request) => {
      expect(request.method).toBe('PATCH');
      expect(new URL(request.url).pathname).toBe(`/api/v1/users/${USER_ID}`);
      sent = await request.json();
      return json(200, { ...USER, enabled: false });
    });
    const client = createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch });

    const result = await updateUser(client, USER_ID, { enabled: false });

    expect(result).toEqual({ ok: true, data: { ...USER, enabled: false } });
    expect(sent).toEqual({ enabled: false });
  });

  it('carries the per-field errors of a 422 problem', async () => {
    const errors = [{ field: 'role', message: 'You cannot change your own role.' }];
    const { client } = clientAnswering(422, { status: 422, code: 'VALIDATION_FAILED', errors });

    expect(await updateUser(client, USER_ID, { role: 'ANALYST' })).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
      fieldErrors: errors,
    });
  });

  it('answers 404 for a user that does not exist', async () => {
    const { client } = clientAnswering(404, { status: 404 });

    expect(await updateUser(client, USER_ID, { name: 'Pedro' })).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 404, code: undefined },
      fieldErrors: [],
    });
  });
});
