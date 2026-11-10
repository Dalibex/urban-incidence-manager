import { screen } from '@testing-library/react';
import type { components } from '@urbanpulse/api-client';
import type { Role } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { fakeApi, json, renderApp } from '../test-utils';
import { incidentPath, landingPath, PATHS } from './access';
import { SECTIONS } from './sections';

/**
 * Regression tests of #141 for the bug #127: an administrator could not open the incident inbox.
 * The permission matrix of docs/security.md and ADR-0020 ("Operator" means MUNICIPAL_OPERATOR or
 * ADMINISTRATOR) let both roles read every incident, so the web must open the inbox and the
 * detail to both, and to no other role.
 */

type Incident = components['schemas']['IncidentResponse'];

const INCIDENT: Incident = {
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
    address: null,
    neighborhood: null,
    district: null,
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
  version: 0,
};

const STAFF_WHO_MANAGE_INCIDENTS = ['MUNICIPAL_OPERATOR', 'ADMINISTRATOR'] as const;
const OTHER_ROLES = ['TECHNICIAN', 'ANALYST', 'CITIZEN'] as const;
// Since #187 the technician also opens the detail, of the incidents assigned to them only.
const ROLES_WITHOUT_DETAIL = ['ANALYST', 'CITIZEN'] as const;

/** The screens of each role: the inbox gained a role in #127, the reports in #42, the map in #196. */
const SECTION_ROLES: Record<string, readonly Role[]> = {
  [PATHS.inbox]: ['MUNICIPAL_OPERATOR', 'ADMINISTRATOR'],
  [PATHS.workQueue]: ['TECHNICIAN'],
  [PATHS.map]: ['MUNICIPAL_OPERATOR', 'ADMINISTRATOR', 'TECHNICIAN'],
  [PATHS.users]: ['ADMINISTRATOR'],
  [PATHS.reports]: ['ANALYST', 'ADMINISTRATOR'],
};

function incidentsApi() {
  return fakeApi({
    'GET /incidents': () =>
      json(200, {
        content: [],
        page: { number: 0, size: 20, totalElements: 0, totalPages: 0 },
      }),
    [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    // The landing of the analyst asks for its report.
    'GET /incident-reports': () => json(200, { rows: [] }),
    // The filters of the technician's work queue.
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

async function navbarLinks(): Promise<string[]> {
  const nav = await screen.findByRole('navigation', { name: 'Main' });
  return [...nav.querySelectorAll('a')].map((link) => link.textContent ?? '');
}

describe('incident inbox access (#127)', () => {
  // T1 and T4
  it.each(STAFF_WHO_MANAGE_INCIDENTS)('shows "Inbox" in the navbar of a %s', async (role) => {
    renderApp(PATHS.inbox, { role, fetch: incidentsApi() });

    expect(await navbarLinks()).toContain('Inbox');
  });

  // T2 and T4
  it.each(STAFF_WHO_MANAGE_INCIDENTS)('opens the inbox to a %s', async (role) => {
    const router = renderApp(PATHS.inbox, { role, fetch: incidentsApi() });

    expect(await screen.findByRole('heading', { level: 1, name: 'Inbox' })).toBeDefined();
    expect(router.state.location.pathname).toBe(PATHS.inbox);
  });

  // T3 and T4
  it.each(STAFF_WHO_MANAGE_INCIDENTS)('opens an incident detail to a %s', async (role) => {
    const router = renderApp(incidentPath(INCIDENT.id), { role, fetch: incidentsApi() });

    expect(await screen.findByRole('heading', { name: INCIDENT.title })).toBeDefined();
    expect(router.state.location.pathname).toBe(incidentPath(INCIDENT.id));
  });

  // T5: the API would refuse them anyway; the web sends them to their own screen.
  it.each([
    ...OTHER_ROLES.map((role) => [role, PATHS.inbox] as const),
    ...ROLES_WITHOUT_DETAIL.map((role) => [role, incidentPath(INCIDENT.id)] as const),
  ])('sends a %s from %s to their own screen', async (role, path) => {
    const fetch = incidentsApi();
    const router = renderApp(path, { role, fetch });

    await screen.findByRole('heading', { level: 1 });
    const { pathname, search } = router.state.location;
    expect(pathname + search).toBe(landingPath(role));
    // Their own screen may load its data (the work queue lists the technician's incidents, which
    // the API scopes), but nothing of the incident they were sent away from.
    const requested = fetch.mock.calls.map(([request]) => new URL(request.url).pathname);
    expect(requested.filter((requestedPath) => requestedPath.includes(INCIDENT.id))).toEqual([]);
  });

  // T6
  it('gives each screen only its roles', () => {
    expect(
      Object.fromEntries(SECTIONS.map(({ path, roles }) => [path, [...roles].sort()])),
    ).toEqual(
      Object.fromEntries(
        Object.entries(SECTION_ROLES).map(([path, roles]) => [path, [...roles].sort()]),
      ),
    );
  });

  it.each([
    ['MUNICIPAL_OPERATOR', ['Inbox', 'Map']],
    ['ADMINISTRATOR', ['Inbox', 'Map', 'Users', 'Reports']],
    ['TECHNICIAN', ['Work queue', 'Map']],
    ['ANALYST', ['Reports']],
  ] as const)('links the navbar of a %s to %j', async (role, links) => {
    renderApp(landingPath(role), { role, fetch: incidentsApi() });

    expect(await navbarLinks()).toEqual(links);
  });
});
