import { screen, within } from '@testing-library/react';
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

  it('opens on the work still to do, from the sign-in and from the menu', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(PATHS.home, { role: 'TECHNICIAN', fetch: queueApi([], requests) });

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Assigned', 'In progress']);
    expect(requests.at(-1)!.getAll('status')).toEqual(['ASSIGNED', 'IN_PROGRESS']);

    await userEvent.click(statusChoice('Resolved'));
    const menu = screen.getByRole('navigation', { name: 'Main' });
    await userEvent.click(within(menu).getByRole('link', { name: 'Work queue' }));

    expect(tickedStatuses()).toEqual(['Assigned', 'In progress']);
    expect(router.state.location.search).toBe('?status=ASSIGNED&status=IN_PROGRESS');
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
});
