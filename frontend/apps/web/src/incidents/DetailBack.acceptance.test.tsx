import { act, cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import type { Role } from '@urbanpulse/domain';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { incidentPath, PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Tests of #433: a detail opened with no origin (a direct link, the audit log, a reload that asks
// to sign in again) leads back to the list with the filters the user last used on it, not to its
// defaults, which the list would then remember in their place.

type Incident = components['schemas']['IncidentResponse'];

const EMPTY_PAGE = { content: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } };

const TOKENS = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const OPERATOR = {
  id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
  name: 'Lucía Martín',
  email: 'lucia.martin@example.com',
  role: 'MUNICIPAL_OPERATOR' as Role,
};

const INCIDENT: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'IN_PROGRESS',
  priority: 'HIGH',
  priorityReason: 'The street is dark near a school.',
  category: null,
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: null,
    addressStatus: 'PENDING' as const,
    addressSource: null,
    neighborhood: null,
    district: null,
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
  version: 2,
};

const DETAIL = incidentPath(INCIDENT.id);
const NO_PAGE = { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } };

function api(user: { id: string; role: Role } = OPERATOR) {
  return fakeApi({
    'POST /sessions': () => json(201, TOKENS),
    'GET /users/me': () => json(200, { ...OPERATOR, ...user }),
    'GET /incidents': () => json(200, EMPTY_PAGE),
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
    [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/classification-changes`]: () => json(200, NO_PAGE),
    [`GET /incidents/${INCIDENT.id}/comments`]: () => json(200, NO_PAGE),
    [`GET /incidents/${INCIDENT.id}/attachments`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/urban-context`]: () =>
      json(200, { status: 'UNAVAILABLE', capturedAt: null, weather: null }),
  });
}

function menuLink(name: string): string | null {
  const menu = screen.getByRole('navigation', { name: 'Main' });
  return within(menu).getByRole('link', { name }).getAttribute('href');
}

async function backTo(name: string) {
  await screen.findByRole('heading', { name: INCIDENT.title });
  await userEvent.click(screen.getByRole('link', { name }));
}

describe('going back from a detail with no origin (#433)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('leads a direct link back to the inbox with the filters last used on it', async () => {
    const router = renderApp(`${PATHS.inbox}?priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      userId: OPERATOR.id,
      fetch: api(),
    });
    await screen.findByText('No incidents match these filters.');

    // A link with no navigation state, as the audit log or a shared address opens the detail.
    await act(() => router.navigate(DETAIL));
    await backTo('Back to the inbox');

    await screen.findByText('No incidents match these filters.');
    expect(router.state.location.search).toBe('?priority=HIGH');
    expect(menuLink('Inbox')).toBe('/inbox?priority=HIGH');
    expect(window.localStorage.getItem(`urbanpulse.list-filters.inbox.${OPERATOR.id}`)).toBe(
      '?priority=HIGH',
    );
  });

  // The web session lives in memory, so a reload goes through the sign-in (#310), which returns to
  // the address of the detail without the screen it was opened from.
  it('leads a reloaded detail back to the inbox with the filters last used on it', async () => {
    renderApp(`${PATHS.inbox}?priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      userId: OPERATOR.id,
      fetch: api(),
    });
    await screen.findByText('No incidents match these filters.');
    cleanup();

    const router = renderApp(DETAIL, { fetch: api() });
    await userEvent.type(await screen.findByLabelText('Email'), OPERATOR.email);
    await userEvent.type(screen.getByLabelText('Password'), 'secret-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await backTo('Back to the inbox');

    await screen.findByText('No incidents match these filters.');
    expect(router.state.location.search).toBe('?priority=HIGH');
    expect(menuLink('Inbox')).toBe('/inbox?priority=HIGH');
  });

  it('leads the technician back to the work queue with the filters last used on it', async () => {
    const technician = { id: 'technician-1', role: 'TECHNICIAN' as Role };
    const router = renderApp(`${PATHS.workQueue}?status=IN_PROGRESS`, {
      role: 'TECHNICIAN',
      userId: technician.id,
      fetch: api(technician),
    });
    await screen.findByText('No incidents match these filters.');

    await act(() => router.navigate(DETAIL));
    await backTo('Back to the work queue');

    await screen.findByText('No incidents match these filters.');
    expect(router.state.location.search).toBe('?status=IN_PROGRESS');
    expect(menuLink('Work queue')).toBe('/work-queue?status=IN_PROGRESS');
  });

  it('leads to the default list when nothing is remembered', async () => {
    const router = renderApp(DETAIL, { role: 'MUNICIPAL_OPERATOR', fetch: api() });

    await backTo('Back to the inbox');

    await screen.findByText('No incidents match these filters.');
    expect(router.state.location.search).toBe('?status=REPORTED');
  });

  it('leads to the default list when the browser refuses its storage', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    const router = renderApp(DETAIL, { role: 'MUNICIPAL_OPERATOR', fetch: api() });

    await backTo('Back to the inbox');

    await screen.findByText('No incidents match these filters.');
    expect(router.state.location.search).toBe('?status=REPORTED');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
