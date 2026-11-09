import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Acceptance tests of #67 for the photos of the staff detail on the web (#194). The API side of the
// cases is in EvidenceApiIT; these check what the operator sees when the API refuses or fails.

type Attachment = components['schemas']['AttachmentResponse'];
type Answer = () => Response | Promise<Response>;

const INCIDENT: components['schemas']['IncidentResponse'] = {
  id: '7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
  title: 'Broken bench',
  description: 'Two slats are missing.',
  status: 'REPORTED',
  priority: null,
  category: null,
  location: {
    latitude: 36.7196,
    longitude: -4.42,
    accuracy: 8,
    address: null,
    neighborhood: null,
    district: null,
  },
  reportedAt: '2026-11-02T09:00:00Z',
  updatedAt: '2026-11-02T09:00:00Z',
  closedAt: null,
};

function attachment(id: string, fileName: string): Attachment {
  return {
    id,
    fileName,
    contentType: 'image/jpeg',
    size: 2048,
    uploadedAt: '2026-11-02T09:05:00Z',
  };
}

const BENCH = attachment('1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e', 'bench.jpg');
const SLATS = attachment('2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f', 'slats.jpg');

function photo(): Response {
  return new Response(new Uint8Array([0xff, 0xd8, 0xff]), {
    status: 200,
    headers: { 'Content-Type': 'image/jpeg' },
  });
}

function problem(status: number, code?: string): Answer {
  return () => json(status, { type: 'about:blank', title: 'Error', status, code });
}

const NO_NETWORK: Answer = () => {
  throw new TypeError('Failed to fetch');
};

function detailApi(list: Answer, content: Record<string, Answer> = {}) {
  return fakeApi({
    [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/attachments`]: list,
    ...Object.fromEntries(
      Object.entries(content).map(([id, answer]) => [`GET /attachments/${id}/content`, answer]),
    ),
  });
}

function requestsTo(fetch: ReturnType<typeof fakeApi>, path: string) {
  return fetch.mock.calls.filter(([request]) => new URL(request.url).pathname === `/api/v1${path}`);
}

function photosPanel(): Promise<HTMLElement> {
  return screen.findByRole('region', { name: 'Photos' });
}

async function figureOf(fileName: string): Promise<HTMLElement> {
  return (await within(await photosPanel()).findByText(fileName)).closest('figure')!;
}

describe('photos of the staff detail, acceptance of #67', () => {
  let revoked: string[];

  beforeEach(() => {
    let created = 0;
    revoked = [];
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:photo-${++created}`);
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url) => void revoked.push(url));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // T6, T10: the API hides a photo the user may not read behind a 404. The tile says so, the
  // other photos stay, and a 4xx is not retried on its own.
  it.each([
    ['404', problem(404, 'NOT_FOUND')],
    ['403', problem(403, 'FORBIDDEN')],
  ])('shows a refused photo (%s) as failed, once, and keeps the others', async (_, refusal) => {
    const fetch = detailApi(() => json(200, [BENCH, SLATS]), {
      [BENCH.id]: photo,
      [SLATS.id]: refusal,
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    const slats = await figureOf(SLATS.fileName);
    expect((await within(slats).findByRole('alert')).textContent).toContain(
      'This photo could not be loaded.',
    );
    expect(within(slats).queryByRole('img')).toBeNull();
    expect(await screen.findByRole('img', { name: BENCH.fileName })).toBeTruthy();
    expect(requestsTo(fetch, `/attachments/${SLATS.id}/content`)).toHaveLength(1);
  });

  // T9, T17: a refused list is an error of its own section; the rest of the detail stays.
  it('shows a refused list as an error with a retry, without retrying it on its own', async () => {
    const fetch = detailApi(problem(403, 'FORBIDDEN'));
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    const panel = await photosPanel();
    expect((await within(panel).findByRole('alert')).textContent).toContain(
      'The photos could not be loaded.',
    );
    expect(within(panel).getByRole('button', { name: 'Try again' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: INCIDENT.title })).toBeTruthy();
    expect(requestsTo(fetch, `/incidents/${INCIDENT.id}/attachments`)).toHaveLength(1);
  });

  // T18 without network: the list says the server cannot be reached, and the retry loads it.
  it('says the server cannot be reached when the list fails without network', async () => {
    const lists = [NO_NETWORK, NO_NETWORK, NO_NETWORK, () => json(200, [BENCH])];
    const fetch = detailApi(() => lists.shift()!(), { [BENCH.id]: photo });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    const panel = await photosPanel();
    expect((await within(panel).findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );

    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('img', { name: BENCH.fileName })).toBeTruthy();
  });

  // T18 without network on one photo: a transient failure is retried before the tile fails, and
  // Try again loads it.
  it('retries a photo that fails without network, then lets the user try again', async () => {
    let online = false;
    const fetch = detailApi(() => json(200, [BENCH]), {
      [BENCH.id]: () => (online ? photo() : NO_NETWORK()),
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'ADMINISTRATOR', fetch });

    const bench = await figureOf(BENCH.fileName);
    expect((await within(bench).findByRole('alert')).textContent).toContain(
      'This photo could not be loaded.',
    );
    expect(requestsTo(fetch, `/attachments/${BENCH.id}/content`)).toHaveLength(3);

    online = true;
    await userEvent.click(within(bench).getByRole('button', { name: 'Try again' }));

    expect(await within(bench).findByRole('img', { name: BENCH.fileName })).toBeTruthy();
  });

  // T7: no photo is shown before its download ends, and none from an address of the API.
  it('shows no image while the photo downloads', async () => {
    let release: () => void = () => undefined;
    const fetch = detailApi(() => json(200, [BENCH]), {
      [BENCH.id]: () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(photo());
        }),
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    const bench = await figureOf(BENCH.fileName);
    expect(await within(bench).findByText('Loading the photo…')).toBeTruthy();
    expect(within(bench).queryByRole('img')).toBeNull();

    release();

    const image = await within(bench).findByRole<HTMLImageElement>('img', { name: BENCH.fileName });
    expect(image.src).toBe('blob:photo-1');
  });

  // T7: the copy of the photo in the browser is released when the detail closes.
  it('releases the object URL of each photo when the detail closes', async () => {
    const fetch = detailApi(() => json(200, [BENCH, SLATS]), {
      [BENCH.id]: photo,
      [SLATS.id]: photo,
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    await screen.findByRole('img', { name: BENCH.fileName });
    await screen.findByRole('img', { name: SLATS.fileName });
    expect(revoked).toEqual([]);

    cleanup();

    expect(revoked.sort()).toEqual(['blob:photo-1', 'blob:photo-2']);
  });
});
