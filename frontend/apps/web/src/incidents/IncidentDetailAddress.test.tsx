import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';
import { PENDING_CONTEXT_CHECKS, PENDING_CONTEXT_INTERVAL_MS } from './incident-queries';

type Incident = components['schemas']['IncidentResponse'];

/**
 * The address of the incident detail while the worker looks it up, some seconds after the report
 * (#375): the detail reads the incident again until the address arrives, for a bounded time (#436).
 */

const RESOLVED: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'REPORTED',
  priority: null,
  category: null,
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
  version: 0,
};

const PENDING: Incident = {
  ...RESOLVED,
  location: { ...RESOLVED.location, address: null, addressStatus: 'PENDING', addressSource: null },
};

const LOOKING_UP = 'Looking up the address…';
const REASON = 'This streetlight belongs to a private estate; please contact its owners.';

const EMPTY_PAGE = {
  content: [],
  page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
};

type Answer = () => Response;

/** The detail of the incident; `incident` answers each read of it. */
function openDetail(incident: Answer) {
  const fetch = fakeApi({
    'GET /categories': () => json(200, []),
    [`GET /incidents/${RESOLVED.id}`]: incident,
    [`GET /incidents/${RESOLVED.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${RESOLVED.id}/attachments`]: () => json(200, []),
    [`GET /incidents/${RESOLVED.id}/urban-context`]: () =>
      json(200, { status: 'UNAVAILABLE', capturedAt: null, weather: null }),
    [`GET /incidents/${RESOLVED.id}/classification-changes`]: () => json(200, EMPTY_PAGE),
    [`GET /incidents/${RESOLVED.id}/comments`]: () => json(200, EMPTY_PAGE),
  });
  renderApp(incidentPath(RESOLVED.id), { role: 'MUNICIPAL_OPERATOR', fetch });
  return fetch;
}

function incidentReads(fetch: ReturnType<typeof fakeApi>): number {
  return fetch.mock.calls.filter(([request]) =>
    new URL(request.url).pathname.endsWith(`/incidents/${RESOLVED.id}`),
  ).length;
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('address of the incident detail while it is looked up (#436)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the address once the worker resolved it, and stops reading', async () => {
    let answer = PENDING;
    const fetch = openDetail(() => json(200, answer));
    expect(await screen.findByText(LOOKING_UP)).toBeTruthy();

    answer = RESOLVED;
    await advance(PENDING_CONTEXT_INTERVAL_MS);

    expect(await screen.findByText('Calle Larios 12')).toBeTruthy();
    expect(screen.queryByText(LOOKING_UP)).toBeNull();
    const reads = incidentReads(fetch);
    await advance(PENDING_CONTEXT_INTERVAL_MS * 4);
    expect(incidentReads(fetch)).toBe(reads);
  });

  it('does not read again an incident whose address is already known', async () => {
    const fetch = openDetail(() => json(200, RESOLVED));
    await screen.findByText('Calle Larios 12');

    await advance(PENDING_CONTEXT_INTERVAL_MS * 4);

    expect(incidentReads(fetch)).toBe(1);
  });

  // The worker gives up after a day; the screen stops long before.
  it('stops reading on its own after the last check', async () => {
    const fetch = openDetail(() => json(200, PENDING));
    await screen.findByText(LOOKING_UP);

    for (let check = 0; check < PENDING_CONTEXT_CHECKS; check++) {
      await advance(PENDING_CONTEXT_INTERVAL_MS);
    }
    expect(incidentReads(fetch)).toBe(PENDING_CONTEXT_CHECKS + 1);

    await advance(PENDING_CONTEXT_INTERVAL_MS * 4);
    expect(incidentReads(fetch)).toBe(PENDING_CONTEXT_CHECKS + 1);
  });

  it('keeps the incident when a read fails, with a notice, and stops reading', async () => {
    let fails = false;
    const fetch = openDetail(() =>
      fails ? json(503, { status: 503, code: 'SERVICE_UNAVAILABLE' }) : json(200, PENDING),
    );
    await screen.findByText(LOOKING_UP);

    fails = true;
    await advance(PENDING_CONTEXT_INTERVAL_MS);

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The incident could not be refreshed.',
    );
    expect(screen.getByRole('heading', { name: RESOLVED.title })).toBeTruthy();
    expect(screen.getByText(LOOKING_UP)).toBeTruthy();
    const reads = incidentReads(fetch);
    await advance(PENDING_CONTEXT_INTERVAL_MS * 4);
    expect(incidentReads(fetch)).toBe(reads);
  });

  // #276: the read that brings the address does not reset a form the operator is filling in.
  it('keeps what was typed in a form when the address arrives', async () => {
    let answer = PENDING;
    openDetail(() => json(200, answer));
    const actions = within(await screen.findByRole('region', { name: 'Actions' }));
    const reject = within(actions.getByRole('form', { name: 'Reject' }));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(reject.getByLabelText('Reason for the citizen'), REASON);

    answer = RESOLVED;
    await advance(PENDING_CONTEXT_INTERVAL_MS);

    expect(await screen.findByText('Calle Larios 12')).toBeTruthy();
    expect(reject.getByLabelText('Reason for the citizen')).toHaveProperty('value', REASON);
  });
});
