import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createApiClient,
  uploadIncidentAttachment,
  type LocalFile,
  type SessionTokens,
  type TokenStore,
} from './index';
import type { components } from './schema';

const BASE_URL = 'https://api.test/api/v1/';
const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';
const UPLOAD_URL = `https://api.test/api/v1/incidents/${INCIDENT_ID}/attachments`;

// The access tokens of `tokens()` expire at 10:15.
const BEFORE_EXPIRY = () => new Date('2026-11-02T10:00:00Z');
const AFTER_EXPIRY = () => new Date('2026-11-02T10:20:00Z');

const PHOTO: LocalFile = { uri: 'file:///photo.jpg', name: 'photo.jpg', type: 'image/jpeg' };

const ATTACHMENT: components['schemas']['AttachmentResponse'] = {
  id: 'c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f',
  fileName: 'photo.jpg',
  contentType: 'image/jpeg',
  size: 2483115,
  uploadedAt: '2026-11-02T10:00:00Z',
};

function tokens(accessToken: string, refreshToken: string): SessionTokens {
  return {
    tokenType: 'Bearer',
    accessToken,
    accessTokenExpiresAt: '2026-11-02T10:15:00Z',
    refreshToken,
    refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
  };
}

function memoryStore(initial: SessionTokens | null): TokenStore {
  let saved = initial;
  return {
    load: async () => saved,
    save: async (next) => {
      saved = next;
    },
    clear: async () => {
      saved = null;
    },
  };
}

// 'pending' leaves the request open: the test drives it with `progress` and `respond`.
type Answer =
  | { status: number; body?: unknown; progress?: [number, number][] }
  | 'network-error'
  | 'timeout'
  | 'pending';

/** An `XMLHttpRequest` that records what it was sent and answers as the test says. */
class FakeRequest {
  method = '';
  url = '';
  timeout = 0;
  headers: Record<string, string> = {};
  body: unknown;
  status = 0;
  responseText = '';
  upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  onabort: (() => void) | null = null;
  aborted = false;

  constructor(private readonly answer: Answer) {}

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name] = value;
  }

  abort() {
    this.aborted = true;
    this.onabort?.();
  }

  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent);
  }

  respond(status: number, body?: unknown) {
    this.status = status;
    this.responseText = body === undefined ? '' : JSON.stringify(body);
    this.onload?.();
  }

  send(body: unknown) {
    this.body = body;
    if (this.answer === 'pending') {
      return;
    }
    if (this.answer === 'network-error') {
      this.onerror?.();
      return;
    }
    if (this.answer === 'timeout') {
      this.ontimeout?.();
      return;
    }
    for (const [loaded, total] of this.answer.progress ?? []) {
      this.progress(loaded, total);
    }
    this.respond(this.answer.status, this.answer.body);
  }
}

/** Requests answered in order; each one is kept so the test can check what was sent. */
function requests(...answers: Answer[]) {
  const sent: FakeRequest[] = [];
  const createRequest = () => {
    const answer = answers[sent.length];
    if (answer === undefined) {
      throw new Error('Unexpected upload');
    }
    const request = new FakeRequest(answer);
    sent.push(request);
    return request as unknown as XMLHttpRequest;
  };
  return { sent, createRequest };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function setUp(tokenStore: TokenStore, fetch = vi.fn<(request: Request) => Promise<Response>>()) {
  const client = createApiClient(BASE_URL, { tokenStore, fetch });
  return { client, fetch };
}

describe('uploadIncidentAttachment', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('posts the photo with the access token and reports its progress', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    const xhr = requests({
      status: 201,
      body: ATTACHMENT,
      progress: [
        [50, 200],
        [200, 200],
      ],
    });
    const onProgress = vi.fn();

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      onProgress,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({ ok: true, data: ATTACHMENT });
    expect(onProgress.mock.calls).toEqual([[0.25], [1]]);
    const [request] = xhr.sent;
    expect(request?.method).toBe('POST');
    expect(request?.url).toBe(UPLOAD_URL);
    expect(request?.headers.Authorization).toBe('Bearer access-1');
    expect(request?.headers['Content-Type']).toBeUndefined();
    // No limit for the whole upload: only a stalled one ends (see the tests below).
    expect(request?.timeout).toBe(0);
    expect(request?.body).toBeInstanceOf(FormData);
  });

  it('never reports more than the whole file, even when more bytes than the total are sent (#222)', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    // React Native on Android reported 170% for a small photo.
    const xhr = requests({ status: 201, body: ATTACHMENT, progress: [[56_100, 33_000]] });
    const onProgress = vi.fn();

    await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      onProgress,
      createRequest: xhr.createRequest,
    });

    expect(onProgress.mock.calls).toEqual([[1]]);
  });

  it('accepts the 200 of a photo that was already attached, such as a retry', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    const xhr = requests({ status: 200, body: ATTACHMENT });

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({ ok: true, data: ATTACHMENT });
  });

  it('returns the status and code of a refused photo', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    const xhr = requests({ status: 413, body: { status: 413, code: 'FILE_TOO_LARGE' } });

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 413, code: 'FILE_TOO_LARGE' },
    });
  });

  it('returns a status without a code when the body is not a problem', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    const xhr = requests({ status: 502 });

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 502, code: undefined },
    });
  });

  it.each(['network-error', 'timeout'] as const)(
    'turns a %s into a network failure',
    async (answer) => {
      const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
      const { client } = setUp(tokenStore);
      const xhr = requests(answer);

      const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
        baseUrl: BASE_URL,
        tokenStore,
        now: BEFORE_EXPIRY,
        createRequest: xhr.createRequest,
      });

      expect(result).toEqual({ ok: false, failure: { kind: 'network' } });
    },
  );

  it('refreshes an expired session through the client and repeats the upload once', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client, fetch } = setUp(tokenStore);
    fetch.mockImplementation(async (request) => {
      const path = new URL(request.url).pathname;
      if (path.endsWith('/sessions/refresh')) {
        return json(200, tokens('access-2', 'refresh-2'));
      }
      return request.headers.get('Authorization') === 'Bearer access-2'
        ? json(200, { id: 'user' })
        : json(401, { status: 401, code: 'UNAUTHORIZED' });
    });
    const xhr = requests(
      { status: 401, progress: [[200, 200]] },
      { status: 201, body: ATTACHMENT, progress: [[200, 200]] },
    );
    const onProgress = vi.fn();

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      onProgress,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({ ok: true, data: ATTACHMENT });
    expect(xhr.sent.map((request) => request.headers.Authorization)).toEqual([
      'Bearer access-1',
      'Bearer access-2',
    ]);
    // The bar starts again with the repeat instead of staying at 100%.
    expect(onProgress.mock.calls).toEqual([[1], [0], [1]]);
  });

  it('refreshes an access token that has already expired before sending the photo', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client, fetch } = setUp(tokenStore);
    fetch.mockImplementation(async (request) => {
      const path = new URL(request.url).pathname;
      if (path.endsWith('/sessions/refresh')) {
        return json(200, tokens('access-2', 'refresh-2'));
      }
      return request.headers.get('Authorization') === 'Bearer access-2'
        ? json(200, { id: 'user' })
        : json(401, { status: 401, code: 'UNAUTHORIZED' });
    });
    const xhr = requests({ status: 201, body: ATTACHMENT });

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: AFTER_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({ ok: true, data: ATTACHMENT });
    // The photo is sent once, already with the new token.
    expect(xhr.sent.map((request) => request.headers.Authorization)).toEqual(['Bearer access-2']);
  });

  it('does not send the photo when an expired session cannot be refreshed', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client, fetch } = setUp(tokenStore);
    fetch.mockResolvedValue(json(401, { status: 401, code: 'UNAUTHORIZED' }));
    const xhr = requests();

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: AFTER_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 401, code: 'UNAUTHORIZED' },
    });
    expect(xhr.sent).toHaveLength(0);
  });

  it('does not send the photo when the API cannot be reached to refresh an expired session', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client, fetch } = setUp(tokenStore);
    fetch.mockRejectedValue(new TypeError('Network request failed'));
    const xhr = requests();

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: AFTER_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({ ok: false, failure: { kind: 'network' } });
    expect(xhr.sent).toHaveLength(0);
  });

  it('does not repeat the upload when the session could not be refreshed', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client, fetch } = setUp(tokenStore);
    fetch.mockResolvedValue(json(401, { status: 401, code: 'UNAUTHORIZED' }));
    const xhr = requests({ status: 401, body: { status: 401, code: 'UNAUTHORIZED' } });

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 401, code: 'UNAUTHORIZED' },
    });
    expect(xhr.sent).toHaveLength(1);
  });

  it('returns a network failure when the API cannot be reached to refresh', async () => {
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client, fetch } = setUp(tokenStore);
    fetch.mockRejectedValue(new TypeError('Network request failed'));
    const xhr = requests({ status: 401 });

    const result = await uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });

    expect(result).toEqual({ ok: false, failure: { kind: 'network' } });
    expect(xhr.sent).toHaveLength(1);
  });

  it('ends an upload that sends nothing for a minute in a network failure', async () => {
    vi.useFakeTimers();
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    const xhr = requests('pending');

    const upload = uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });
    await vi.advanceTimersByTimeAsync(59_000);
    xhr.sent[0]?.progress(100, 1_000);
    await vi.advanceTimersByTimeAsync(60_000);

    await expect(upload).resolves.toEqual({ ok: false, failure: { kind: 'network' } });
    expect(xhr.sent[0]?.aborted).toBe(true);
  });

  it('keeps a slow upload that is still moving after more than a minute', async () => {
    vi.useFakeTimers();
    const tokenStore = memoryStore(tokens('access-1', 'refresh-1'));
    const { client } = setUp(tokenStore);
    const xhr = requests('pending');

    const upload = uploadIncidentAttachment(client, INCIDENT_ID, PHOTO, {
      baseUrl: BASE_URL,
      tokenStore,
      now: BEFORE_EXPIRY,
      createRequest: xhr.createRequest,
    });
    await vi.advanceTimersByTimeAsync(0);
    for (const loaded of [250, 500, 750, 1_000]) {
      await vi.advanceTimersByTimeAsync(50_000);
      xhr.sent[0]?.progress(loaded, 1_000);
    }
    xhr.sent[0]?.respond(201, ATTACHMENT);

    await expect(upload).resolves.toEqual({ ok: true, data: ATTACHMENT });
    expect(xhr.sent[0]?.aborted).toBe(false);
  });
});
