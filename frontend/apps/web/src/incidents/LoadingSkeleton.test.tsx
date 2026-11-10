import { act, screen, within } from '@testing-library/react';
import { SKELETON_DELAY_MS, SLOW_LOADING_MS } from '@urbanpulse/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { incidentPath, PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

/**
 * The inbox while its first page loads, and the incident detail while its incident loads (#174),
 * with the cases of the issue: a quick answer, a slow one, a failure, and what screen readers get.
 */

const PAGE = {
  content: [
    {
      id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
      title: 'Street light off',
      status: 'REPORTED',
      priority: null,
      category: null,
      reportedAt: '2026-11-02T09:58:31Z',
      updatedAt: '2026-11-02T09:58:31Z',
    },
  ],
  page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
};

function inbox(listing: () => Response | Promise<Response>) {
  return fakeApi({
    'GET /incidents': listing,
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

/** A listing that never answers, so the page stays loading. */
const NO_ANSWER = () => new Promise<Response>(() => {});

function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

describe('inbox while it loads (#174)', () => {
  beforeEach(() => {
    // The clock moves on its own too, so the queries and `findBy` work as with real timers.
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('says it is loading at once, and draws the table only after a moment', async () => {
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch: inbox(NO_ANSWER) });

    expect(await screen.findByRole('status')).toHaveProperty('textContent', 'Loading incidents…');
    expect(screen.queryByTestId('skeleton')).toBeNull();

    advance(SKELETON_DELAY_MS);
    const skeleton = screen.getByTestId('skeleton');
    expect(within(skeleton).getByText('Title')).toBeDefined();
    // Only the status is read: the shape is hidden from screen readers.
    expect(skeleton.getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('never draws the skeleton when the answer is quick', async () => {
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch: inbox(() => json(200, PAGE)) });

    expect(await screen.findByText('Street light off')).toBeDefined();
    advance(SKELETON_DELAY_MS);
    expect(screen.queryByTestId('skeleton')).toBeNull();
  });

  it('says the server is slow when the answer takes long', async () => {
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch: inbox(NO_ANSWER) });
    await screen.findByRole('status');

    advance(SLOW_LOADING_MS);
    expect(screen.getByRole('status').textContent).toBe(
      'Still loading incidents. The server is taking longer than usual.',
    );
    expect(screen.getByTestId('skeleton')).toBeDefined();
  });

  it('keeps the filters when the incidents fail, and offers to try again', async () => {
    renderApp(PATHS.inbox, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inbox(() => json(500, { status: 500 })),
    });

    expect(await screen.findByText(/The incidents could not be loaded/)).toBeDefined();
    expect(screen.getByRole('group', { name: 'Status' })).toBeDefined();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
    expect(screen.queryByTestId('skeleton')).toBeNull();
  });
});

const INCIDENT = {
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

const NO_ENTRIES = { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } };

/** The detail of `INCIDENT`; `incident` answers the incident itself, the rest answer at once. */
function detail(incident: () => Response | Promise<Response>) {
  return fakeApi({
    [`GET /incidents/${INCIDENT.id}`]: incident,
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/classification-changes`]: () => json(200, NO_ENTRIES),
    [`GET /incidents/${INCIDENT.id}/comments`]: () => json(200, NO_ENTRIES),
    [`GET /incidents/${INCIDENT.id}/attachments`]: () => json(200, []),
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

describe('incident detail while it loads (#174)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('says it is loading at once, and draws the panels only after a moment', async () => {
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: detail(NO_ANSWER) });

    expect(await screen.findByRole('status')).toHaveProperty(
      'textContent',
      'Loading the incident…',
    );
    expect(screen.queryByTestId('skeleton')).toBeNull();

    advance(SKELETON_DELAY_MS);
    const skeleton = screen.getByTestId('skeleton');
    // The panels keep their titles, in the places the real ones take.
    for (const title of ['Actions', 'Report', 'Location']) {
      expect(within(skeleton).getByText(title)).toBeDefined();
    }
    // Only the status is read: the shape and its titles are hidden from screen readers.
    expect(skeleton.getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByRole('heading')).toBeNull();
    // The way back does not wait for the incident.
    expect(screen.getByRole('link', { name: 'Back to the inbox' })).toBeDefined();
  });

  it('never draws the skeleton when the answer is quick', async () => {
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: detail(() => json(200, INCIDENT)),
    });

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Street light off' }),
    ).toBeDefined();
    advance(SKELETON_DELAY_MS);
    expect(screen.queryByTestId('skeleton')).toBeNull();
  });

  it('says the server is slow when the answer takes long', async () => {
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: detail(NO_ANSWER) });
    await screen.findByRole('status');

    advance(SLOW_LOADING_MS);
    expect(screen.getByRole('status').textContent).toBe(
      'Still loading the incident. The server is taking longer than usual.',
    );
    expect(screen.getByTestId('skeleton')).toBeDefined();
  });

  it('replaces the skeleton with the failure, and offers to try again', async () => {
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: detail(() => json(500, { status: 500 })),
    });

    expect(await screen.findByText(/The incident could not be loaded/)).toBeDefined();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
    expect(screen.getByRole('link', { name: 'Back to the inbox' })).toBeDefined();
    expect(screen.queryByTestId('skeleton')).toBeNull();
  });
});
