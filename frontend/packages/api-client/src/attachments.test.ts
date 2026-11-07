import { describe, expect, it, vi } from 'vitest';

import {
  createApiClient,
  getAttachmentContent,
  listIncidentAttachments,
  type SessionTokens,
  type TokenStore,
} from './index';
import type { components } from './schema';

const BASE_URL = 'https://api.test/api/v1';
const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';
const ATTACHMENT_ID = 'c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f';

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const ATTACHMENT: components['schemas']['AttachmentResponse'] = {
  id: ATTACHMENT_ID,
  fileName: 'street-light.jpg',
  contentType: 'image/jpeg',
  size: 2483115,
  uploadedAt: '2026-11-02T10:00:00Z',
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

/** A client whose requests go to `answer`; `sent` keeps each request to check what was asked. */
function clientAnswering(answer: (request: Request) => Promise<Response>) {
  const sent: Request[] = [];
  const fetch = vi.fn(async (request: Request) => {
    sent.push(request);
    return answer(request);
  });
  return { client: createApiClient(BASE_URL, { tokenStore: memoryStore(TOKENS), fetch }), sent };
}

describe('listIncidentAttachments', () => {
  it('returns the metadata of the photos and sends the access token', async () => {
    const { client, sent } = clientAnswering(async () => json(200, [ATTACHMENT]));

    const result = await listIncidentAttachments(client, INCIDENT_ID);

    expect(result).toEqual({ ok: true, data: [ATTACHMENT] });
    expect(sent[0]?.url).toBe(`${BASE_URL}/incidents/${INCIDENT_ID}/attachments`);
    expect(sent[0]?.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('returns the status and code of a refused listing', async () => {
    const { client } = clientAnswering(async () =>
      json(403, { status: 403, code: 'FORBIDDEN' }, 'application/problem+json'),
    );

    const result = await listIncidentAttachments(client, INCIDENT_ID);

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 403, code: 'FORBIDDEN' },
    });
  });
});

describe('getAttachmentContent', () => {
  it('returns the file as a Blob, downloaded with the access token', async () => {
    const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);
    const { client, sent } = clientAnswering(
      async () => new Response(bytes, { status: 200, headers: { 'Content-Type': 'image/jpeg' } }),
    );

    const result = await getAttachmentContent(client, ATTACHMENT_ID);

    expect(result.ok).toBe(true);
    const blob = result.ok ? result.data : undefined;
    expect(blob).toBeInstanceOf(Blob);
    expect(blob?.type).toBe('image/jpeg');
    expect(new Uint8Array(await blob!.arrayBuffer())).toEqual(bytes);
    expect(sent[0]?.url).toBe(`${BASE_URL}/attachments/${ATTACHMENT_ID}/content`);
    expect(sent[0]?.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('reads the problem of a failed download, although the success is binary', async () => {
    const { client } = clientAnswering(async () =>
      json(404, { status: 404, code: 'NOT_FOUND' }, 'application/problem+json'),
    );

    const result = await getAttachmentContent(client, ATTACHMENT_ID);

    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 404, code: 'NOT_FOUND' },
    });
  });

  it('returns a network failure when the API cannot be reached', async () => {
    const { client } = clientAnswering(async () => {
      throw new TypeError('Failed to fetch');
    });

    const result = await getAttachmentContent(client, ATTACHMENT_ID);

    expect(result).toEqual({ ok: false, failure: { kind: 'network' } });
  });
});
