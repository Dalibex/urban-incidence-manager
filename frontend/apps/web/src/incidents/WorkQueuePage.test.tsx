import { act, cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];

const ASSIGNED: IncidentSummary = {
  id: '9b2e4f1a-3c5d-4e6f-8a7b-1c2d3e4f5a6b',
  title: 'Pothole on Calle Larios',
  status: 'ASSIGNED',
  priority: 'HIGH',
  category: { id: 'c1', name: 'Pavement' },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-03T08:12:00Z',
};

function pageOf(content: IncidentSummary[]) {
  return {
    content,
    page: {
      number: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length ? 1 : 0,
    },
  };
}

/** A fake API whose listing answers `content`; `requests` keeps the query of each call. */
function queueApi(content: IncidentSummary[], requests: URLSearchParams[] = []) {
  return fakeApi({
    'GET /incidents': (request) => {
      requests.push(new URL(request.url).searchParams);
      return json(200, pageOf(content));
    },
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

function statusChoice(name: string): HTMLInputElement {
  return within(screen.getByRole('group', { name: 'Status' })).getByRole('checkbox', { name });
}

/** The labels of the ticked statuses, in the order the bar shows them. */
function tickedStatuses(): (string | null | undefined)[] {
  return within(screen.getByRole('group', { name: 'Status' }))
    .getAllByRole<HTMLInputElement>('checkbox')
    .filter((checkbox) => checkbox.checked)
    .map((checkbox) => checkbox.closest('label')?.textContent);
}

describe('technician work queue (#187)', () => {
  it('lists the incidents the API returns for the technician', async () => {
    renderApp(PATHS.workQueue, { role: 'TECHNICIAN', fetch: queueApi([ASSIGNED]) });

    expect(await screen.findByRole('heading', { level: 1, name: 'Work queue' })).toBeDefined();
    const table = await screen.findByRole('table');
    expect(within(table).getByText(ASSIGNED.title)).toBeDefined();
    expect(within(table).getByText('Pavement')).toBeDefined();
  });

  // T14
  it('shows an empty queue as an empty state, not as an error', async () => {
    renderApp(PATHS.workQueue, { role: 'TECHNICIAN', fetch: queueApi([]) });

    expect(await screen.findByText('No incidents are assigned to you.')).toBeDefined();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  // The menu opens the defaults the first time (routing/sections.test.ts) and the last filters
  // afterwards (#263).
  it('opens on the work still to do, from the sign-in', async () => {
    const requests: URLSearchParams[] = [];
    renderApp(PATHS.home, { role: 'TECHNICIAN', fetch: queueApi([], requests) });

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Assigned', 'In progress']);
    expect(requests.at(-1)!.getAll('status')).toEqual(['ASSIGNED', 'IN_PROGRESS']);
  });

  // #263, AC1
  it('reopens the queue from the menu with the filters last used on it', async () => {
    const router = renderApp(PATHS.home, { role: 'TECHNICIAN', fetch: queueApi([]) });
    await screen.findByText('No incidents match these filters.');

    await userEvent.click(statusChoice('Resolved'));
    await act(() => router.navigate('/elsewhere'));
    const menu = screen.getByRole('navigation', { name: 'Main' });
    await userEvent.click(within(menu).getByRole('link', { name: 'Work queue' }));

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Assigned', 'In progress', 'Resolved']);
    expect(router.state.location.search).toBe(
      '?status=ASSIGNED&status=IN_PROGRESS&status=RESOLVED',
    );
  });

  // #263, AC2
  it('gives another technician of the browser their own filters, or the defaults', async () => {
    const first = renderApp(PATHS.home, { role: 'TECHNICIAN', fetch: queueApi([]) });
    await screen.findByText('No incidents match these filters.');
    await userEvent.click(statusChoice('Resolved'));
    await act(() => first.navigate('/elsewhere'));
    cleanup();

    renderApp('/elsewhere', { role: 'TECHNICIAN', userId: 'user-2', fetch: queueApi([]) });
    const menu = screen.getByRole('navigation', { name: 'Main' });
    await userEvent.click(within(menu).getByRole('link', { name: 'Work queue' }));

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Assigned', 'In progress']);
  });

  it('asks the API for the statuses chosen, starting again on the first page', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.workQueue}?status=ASSIGNED&page=2`, {
      role: 'TECHNICIAN',
      fetch: queueApi([], requests),
    });
    await screen.findByText('No incidents match these filters.');

    await userEvent.click(statusChoice('In progress'));

    expect(router.state.location.pathname).toBe(PATHS.workQueue);
    expect(router.state.location.search).toBe('?status=ASSIGNED&status=IN_PROGRESS');
    expect(requests.at(-1)!.getAll('status')).toEqual(['ASSIGNED', 'IN_PROGRESS']);
    expect(requests.at(-1)!.get('page')).toBe('0');
  });

  // #265, test sub-issue T2: the API keeps the technician's scope; the queue only adds the filter.
  it('asks the API for the two departments the technician ticks', async () => {
    const maintenance = { id: 'd4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f7a', name: 'Maintenance' };
    const lightsTeam = { id: '0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d', name: 'Lights team' };
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.workQueue}?status=ASSIGNED&status=IN_PROGRESS`, {
      role: 'TECHNICIAN',
      fetch: fakeApi({
        'GET /incidents': (request) => {
          requests.push(new URL(request.url).searchParams);
          return json(200, pageOf([]));
        },
        'GET /categories': () => json(200, []),
        'GET /departments': () => json(200, [maintenance, lightsTeam]),
      }),
    });
    await userEvent.click(
      await screen.findByRole('button', { name: 'Department: All departments' }),
    );

    const list = within(screen.getByRole('group', { name: 'Department' }));
    await userEvent.click(list.getByRole('checkbox', { name: 'Maintenance' }));
    await userEvent.click(list.getByRole('checkbox', { name: 'Lights team' }));

    const both = [lightsTeam.id, maintenance.id];
    expect(new URLSearchParams(router.state.location.search).getAll('departmentId')).toEqual(both);
    expect(requests.at(-1)!.getAll('departmentId')).toEqual(both);
    // The statuses of the queue stay as they were.
    expect(requests.at(-1)!.getAll('status')).toEqual(['ASSIGNED', 'IN_PROGRESS']);
    expect(screen.getByRole('button', { name: 'Department: 2 departments' })).toBeDefined();
  });
});
