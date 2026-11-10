import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Attachment = components['schemas']['AttachmentResponse'];
type Answer = () => Response;

const INCIDENT: components['schemas']['IncidentResponse'] = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'It has been off for three days.',
  status: 'REPORTED',
  priority: null,
  category: null,
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: null,
    neighborhood: null,
    district: null,
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
};

function attachment(id: string, fileName: string): Attachment {
  return {
    id,
    fileName,
    contentType: 'image/jpeg',
    size: 1024,
    uploadedAt: '2026-11-02T10:00:00Z',
  };
}

const LAMP = attachment('c4d5e6f7-a8b9-4c0d-9e1f-2a3b4c5d6e7f', 'street-light.jpg');
const POLE = attachment('d5e6f7a8-b9c0-4d1e-8f2a-3b4c5d6e7f80', 'pole.jpg');

function photo(): Response {
  return new Response(new Uint8Array([0xff, 0xd8, 0xff]), {
    status: 200,
    headers: { 'Content-Type': 'image/jpeg' },
  });
}

const SERVER_ERROR = () => json(500, { status: 500, code: 'INTERNAL_ERROR' });

/** The detail of `INCIDENT`; `content` answers the download of each photo by its id. */
function detailApi(list: Answer, content: Record<string, Answer> = {}) {
  return fakeApi({
    [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/classification-changes`]: () =>
      json(200, { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } }),
    [`GET /incidents/${INCIDENT.id}/comments`]: () =>
      json(200, { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } }),
    [`GET /incidents/${INCIDENT.id}/attachments`]: list,
    ...Object.fromEntries(
      Object.entries(content).map(([id, answer]) => [`GET /attachments/${id}/content`, answer]),
    ),
  });
}

function photosPanel(): HTMLElement {
  return screen.getByRole('region', { name: 'Photos' });
}

function findPhotosPanel(): Promise<HTMLElement> {
  return screen.findByRole('region', { name: 'Photos' });
}

function figureOf(fileName: string): HTMLElement {
  return within(photosPanel()).getByText(fileName).closest('figure')!;
}

describe('photo gallery in the incident detail (#194)', () => {
  let objectUrls: number;

  beforeEach(() => {
    // jsdom has no object URLs; each photo gets its own fake one.
    objectUrls = 0;
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:photo-${++objectUrls}`);
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The access token on the download is checked in packages/api-client (attachments.test.ts).
  it('shows each downloaded photo through the API, never through a public URL', async () => {
    const fetch = detailApi(() => json(200, [LAMP]), { [LAMP.id]: photo });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    const image = await screen.findByRole<HTMLImageElement>('img', { name: LAMP.fileName });

    expect(image.src).toBe('blob:photo-1');
    expect(fetch.mock.calls.map(([request]) => new URL(request.url).pathname)).toContain(
      `/api/v1/attachments/${LAMP.id}/content`,
    );
  });

  it('says so when the incident has no photos', async () => {
    renderApp(incidentPath(INCIDENT.id), {
      role: 'ADMINISTRATOR',
      fetch: detailApi(() => json(200, [])),
    });

    expect(await screen.findByText('No photos were attached.')).toBeTruthy();
  });

  // T18: one photo fails to load.
  it('keeps the other photos when one fails, and loads it again on retry', async () => {
    let poleFails = true;
    const fetch = detailApi(() => json(200, [LAMP, POLE]), {
      [LAMP.id]: photo,
      [POLE.id]: () => (poleFails ? SERVER_ERROR() : photo()),
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    await screen.findByRole('img', { name: LAMP.fileName });
    const pole = figureOf(POLE.fileName);
    expect((await within(pole).findByRole('alert')).textContent).toContain(
      'This photo could not be loaded.',
    );
    expect(within(pole).queryByRole('img')).toBeNull();

    poleFails = false;
    await userEvent.click(within(pole).getByRole('button', { name: 'Try again' }));

    expect(await within(pole).findByRole('img', { name: POLE.fileName })).toBeTruthy();
    expect(screen.getByRole('img', { name: LAMP.fileName })).toBeTruthy();
  });

  // T18: `5xx` on the list.
  it('shows an error with a retry when the list of photos fails', async () => {
    let listFails = true;
    const fetch = detailApi(() => (listFails ? SERVER_ERROR() : json(200, [LAMP])), {
      [LAMP.id]: photo,
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });

    const panel = await findPhotosPanel();
    expect((await within(panel).findByRole('alert')).textContent).toContain(
      'The photos could not be loaded.',
    );
    expect(screen.getByRole('heading', { name: 'Street light off' })).toBeTruthy();

    listFails = false;
    await userEvent.click(within(panel).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('img', { name: LAMP.fileName })).toBeTruthy();
  });
});
