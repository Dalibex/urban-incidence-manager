import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { onlineManager } from '@tanstack/react-query';
import type { components } from '@urbanpulse/api-client';
import { afterEach, describe, expect, it } from 'vitest';

import { createQueryClient } from '../query-client';
import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];

/**
 * The incident detail without network or with a failed reload in the background (#276): A, the
 * browser reports no network; B, a reload of an incident already on screen fails.
 */

const REPORTED: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'REPORTED',
  priority: 'HIGH',
  priorityReason: 'The street is dark near a school.',
  category: { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' },
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: 'Calle Larios 12',
    addressStatus: 'AVAILABLE' as const,
    addressSource: {
      name: 'OpenStreetMap',
      attribution: '© OpenStreetMap contributors',
      url: 'https://www.openstreetmap.org/copyright',
    },
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
  version: 2,
};

const REASON = 'This streetlight belongs to a private estate; please contact its owners.';

const EMPTY_PAGE = {
  content: [],
  page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
};

type Answer = () => Response;

const NO_NETWORK: Answer = () => {
  throw new TypeError('Failed to fetch');
};

interface Server {
  /** How the API answers the next reads of the incident; it answers it when unset. */
  detail?: Answer;
  rejections: number;
}

function api(server: Server) {
  return fakeApi({
    'GET /categories': () => json(200, [REPORTED.category]),
    [`GET /incidents/${REPORTED.id}`]: () => server.detail?.() ?? json(200, REPORTED),
    [`GET /incidents/${REPORTED.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${REPORTED.id}/attachments`]: () => json(200, []),
    // The weather of the Context section (#308); these tests do not look at it.
    [`GET /incidents/${REPORTED.id}/urban-context`]: () =>
      json(200, { status: 'UNAVAILABLE', capturedAt: null, weather: null }),
    [`GET /incidents/${REPORTED.id}/classification-changes`]: () => json(200, EMPTY_PAGE),
    [`GET /incidents/${REPORTED.id}/comments`]: () => json(200, EMPTY_PAGE),
    [`POST /incidents/${REPORTED.id}/rejection`]: () => {
      server.rejections++;
      // The browser reports no network, so the request does not reach the API.
      return NO_NETWORK();
    },
  });
}

async function rejectForm() {
  const actions = within(await screen.findByRole('region', { name: 'Actions' }));
  return within(actions.getByRole('form', { name: 'Reject' }));
}

// What the browser's `offline` and `online` events tell React Query; it does not read
// `navigator.onLine` by itself.
function goOffline() {
  onlineManager.setOnline(false);
}

afterEach(() => {
  onlineManager.setOnline(true);
});

describe('incident detail without network (#276 A)', () => {
  it('says the server cannot be reached on an action, and sends nothing when the network is back', async () => {
    const server: Server = { rejections: 0 };
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const reject = await rejectForm();
    goOffline();

    await userEvent.type(reject.getByLabelText('Reason for the citizen'), REASON);
    await userEvent.click(reject.getByRole('button', { name: 'Reject the incident' }));

    expect((await reject.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(reject.getByLabelText('Reason for the citizen')).toHaveProperty('value', REASON);
    expect(server.rejections).toBe(1);

    // Rejecting is final: an attempt the operator gave up on must not be sent by itself.
    onlineManager.setOnline(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(server.rejections).toBe(1);
  });

  it('says the server cannot be reached on a read, and loads on retry', async () => {
    goOffline();
    const server: Server = { detail: NO_NETWORK, rejections: 0 };
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );

    // *Try again* asks even while the browser still says it is offline.
    server.detail = undefined;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('heading', { name: REPORTED.title })).toBeDefined();
  });

  it('loads a read that failed offline by itself when the network comes back', async () => {
    goOffline();
    const server: Server = { detail: NO_NETWORK, rejections: 0 };
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    await screen.findByRole('alert');

    server.detail = undefined;
    onlineManager.setOnline(true);

    expect(await screen.findByRole('heading', { name: REPORTED.title })).toBeDefined();
  });
});

describe('incident detail after a failed reload in the background (#276 B)', () => {
  it('keeps the incident and what was typed, with a notice, and refreshes on retry', async () => {
    const server: Server = { rejections: 0 };
    const queryClient = createQueryClient({ retryDelay: 0 });
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server),
      queryClient,
    });
    const reject = await rejectForm();
    await userEvent.type(reject.getByLabelText('Reason for the citizen'), REASON);

    // As when the operator comes back to the tab after 30 s and the backend is down.
    server.detail = () => json(503, { status: 503 });
    await queryClient.refetchQueries({ queryKey: ['incidents', REPORTED.id], exact: true });

    const notice = await screen.findByRole('alert');
    expect(notice.textContent).toContain('The incident could not be refreshed.');
    expect(screen.getByRole('heading', { name: REPORTED.title })).toBeDefined();
    expect(reject.getByLabelText('Reason for the citizen')).toHaveProperty('value', REASON);

    server.detail = undefined;
    await userEvent.click(within(notice).getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(reject.getByLabelText('Reason for the citizen')).toHaveProperty('value', REASON);
  });

  it('says the server cannot be reached when the reload fails without network', async () => {
    const server: Server = { rejections: 0 };
    const queryClient = createQueryClient({ retryDelay: 0 });
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server),
      queryClient,
    });
    await rejectForm();

    server.detail = NO_NETWORK;
    await queryClient.refetchQueries({ queryKey: ['incidents', REPORTED.id], exact: true });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The incident could not be refreshed: the server cannot be reached.',
    );
    expect(screen.getByRole('heading', { name: REPORTED.title })).toBeDefined();
  });

  it('still shows the full-page failure when the first load fails', async () => {
    const server: Server = { detail: () => json(503, { status: 503 }), rejections: 0 };
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The incident could not be loaded.',
    );
    expect(screen.queryByRole('region', { name: 'Actions' })).toBeNull();
  });

  it('still says the incident was not found on a 404', async () => {
    const server: Server = { detail: () => json(404, { status: 404 }), rejections: 0 };
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    expect(await screen.findByRole('heading', { name: 'Incident not found' })).toBeDefined();
  });

  // A technician whose incident was reassigned meanwhile can no longer see it.
  it('says the incident was not found when a reload answers 404', async () => {
    const server: Server = { rejections: 0 };
    const queryClient = createQueryClient({ retryDelay: 0 });
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server),
      queryClient,
    });
    await rejectForm();

    server.detail = () => json(404, { status: 404 });
    await queryClient.refetchQueries({ queryKey: ['incidents', REPORTED.id], exact: true });

    expect(await screen.findByRole('heading', { name: 'Incident not found' })).toBeDefined();
    expect(screen.queryByRole('region', { name: 'Actions' })).toBeNull();
  });
});
