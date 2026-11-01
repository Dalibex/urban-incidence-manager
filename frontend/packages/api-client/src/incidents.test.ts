import { describe, expect, it, vi } from 'vitest';

import { createApiClient, getIncident, listIncidents, type TokenStore } from './index';

const BASE_URL = 'https://api.test/api/v1';
const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';

const signedOut: TokenStore = {
  load: async () => null,
  save: async () => {},
  clear: async () => {},
};

function clientAnswering(status: number, body: unknown) {
  const fetch = vi.fn<(request: Request) => Promise<Response>>(
    async () =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
  );
  return { fetch, client: createApiClient(BASE_URL, { tokenStore: signedOut, fetch }) };
}

describe('incident calls', () => {
  it('sends the page and repeats status once per value, as the contract expects', async () => {
    const { fetch, client } = clientAnswering(200, { content: [], page: {} });

    await listIncidents(client, { page: 2, size: 20, status: ['REPORTED', 'VALIDATED'] });

    const url = new URL(fetch.mock.calls[0]![0].url);
    expect(url.pathname).toBe('/api/v1/incidents');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.getAll('status')).toEqual(['REPORTED', 'VALIDATED']);
  });

  it('reports a missing incident as a 404 problem', async () => {
    const { fetch, client } = clientAnswering(404, { status: 404 });

    const result = await getIncident(client, INCIDENT_ID);

    expect(new URL(fetch.mock.calls[0]![0].url).pathname).toBe(`/api/v1/incidents/${INCIDENT_ID}`);
    expect(result).toEqual({
      ok: false,
      failure: { kind: 'problem', status: 404, code: undefined },
    });
  });
});
