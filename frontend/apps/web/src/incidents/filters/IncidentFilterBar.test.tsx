import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { createQueryClient } from '../../query-client';
import { SessionProvider } from '../../session/SessionContext';
import { fakeApi, json, userWith } from '../../test-utils';
import { IncidentFilterBar } from './IncidentFilterBar';
import type { IncidentFilters } from './incident-filters';

const LIGHTING = {
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  name: 'Street lighting',
  description: null,
};
const PARKS = { id: 'c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f', name: 'Parks', description: null };
const MAINTENANCE = { id: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e', name: 'Maintenance' };

const OPTIONS_API = {
  'GET /categories': () => json(200, [PARKS, LIGHTING]),
  'GET /departments': () => json(200, [MAINTENANCE]),
};

/** The bar with its filters kept in a state, as a screen keeps them; `changes` records each change. */
function renderBar(
  initial: IncidentFilters = {},
  fetch: (request: Request) => Promise<Response> = fakeApi(OPTIONS_API),
) {
  const changes = vi.fn<(filters: IncidentFilters) => void>();
  function Screen() {
    const [filters, setFilters] = useState(initial);
    return (
      <IncidentFilterBar
        filters={filters}
        onChange={(next) => {
          changes(next);
          setFilters(next);
        }}
      />
    );
  }
  render(
    <QueryClientProvider client={createQueryClient({ retryDelay: 0 })}>
      <SessionProvider initialUser={userWith('MUNICIPAL_OPERATOR')} fetch={fetch}>
        <Screen />
      </SessionProvider>
    </QueryClientProvider>,
  );
  return changes;
}

function field(name: string): HTMLSelectElement | HTMLInputElement {
  return screen.getByLabelText(name);
}

function statusChoice(name: string): HTMLInputElement {
  return within(screen.getByRole('group', { name: 'Status' })).getByRole('checkbox', { name });
}

async function optionsLoaded() {
  await within(screen.getByLabelText('Category')).findByRole('option', { name: 'Parks' });
  await within(screen.getByLabelText('Department')).findByRole('option', { name: 'Maintenance' });
}

describe('IncidentFilterBar', () => {
  it('offers every status, priority, active category and active department', async () => {
    renderBar();
    await optionsLoaded();

    const names = (label: string) =>
      within(field(label) as HTMLSelectElement)
        .getAllByRole('option')
        .map((option) => option.textContent);
    const statuses = within(screen.getByRole('group', { name: 'Status' }))
      .getAllByRole('checkbox')
      .map((checkbox) => checkbox.closest('label')?.textContent);
    expect(statuses).toEqual([
      'Reported',
      'Validated',
      'Rejected',
      'Assigned',
      'In progress',
      'Resolved',
      'Reopened',
      'Closed',
    ]);
    expect(names('Priority')).toEqual(['All priorities', 'High', 'Medium', 'Low']);
    expect(names('Category')).toEqual(['All categories', 'Parks', 'Street lighting']);
    expect(names('Department')).toEqual(['All departments', 'Maintenance']);
  });

  it('applies each choice at once and keeps the other filters', async () => {
    const changes = renderBar({ status: ['REPORTED'] });
    await optionsLoaded();

    await userEvent.selectOptions(field('Category'), 'Street lighting');
    await userEvent.selectOptions(field('Priority'), 'High');
    await userEvent.selectOptions(field('Department'), 'Maintenance');

    expect(changes).toHaveBeenLastCalledWith({
      status: ['REPORTED'],
      categoryId: LIGHTING.id,
      priority: 'HIGH',
      departmentId: MAINTENANCE.id,
    });
  });

  it('removes a filter when its "All" option is chosen', async () => {
    const changes = renderBar({ categoryId: LIGHTING.id, priority: 'LOW' });
    await optionsLoaded();

    await userEvent.selectOptions(field('Priority'), 'All priorities');

    expect(changes).toHaveBeenLastCalledWith({ categoryId: LIGHTING.id });
  });

  // #217
  it('adds a status to the ones chosen, in the order of the lifecycle', async () => {
    const changes = renderBar({ status: ['IN_PROGRESS'] });

    await userEvent.click(statusChoice('Assigned'));

    expect(changes).toHaveBeenLastCalledWith({ status: ['ASSIGNED', 'IN_PROGRESS'] });
    expect(statusChoice('Assigned').checked).toBe(true);
    expect(statusChoice('In progress').checked).toBe(true);
  });

  // #217, T5
  it('removes the status filter when its last status is unticked', async () => {
    const changes = renderBar({ status: ['ASSIGNED'], priority: 'LOW' });

    await userEvent.click(statusChoice('Assigned'));

    expect(changes).toHaveBeenLastCalledWith({ priority: 'LOW' });
  });

  it('takes the reporting days and keeps the first one before the last one', async () => {
    const changes = renderBar({ reportedFrom: '2026-11-01', reportedTo: '2026-11-05' });

    await userEvent.clear(field('Reported from'));
    expect(changes).toHaveBeenLastCalledWith({ reportedTo: '2026-11-05' });

    await userEvent.type(field('Reported from'), '2026-11-09');
    expect(changes).toHaveBeenLastCalledWith({
      reportedFrom: '2026-11-09',
      reportedTo: '2026-11-09',
    });
  });

  // The latest incidents in one click (#177): the days end today in Málaga, and keep the rest.
  it('sets the days of a quick range ending today, and marks the one that applies', async () => {
    // 23:30 in UTC is already the next day in Málaga (UTC+1 in November).
    vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-11T23:30:00Z') });
    try {
      const changes = renderBar({ priority: 'HIGH' });
      const ranges = within(screen.getByRole('group', { name: 'Reported in' }));

      await userEvent.click(ranges.getByRole('button', { name: 'Last 7 days' }));

      expect(changes).toHaveBeenLastCalledWith({
        priority: 'HIGH',
        reportedFrom: '2026-11-06',
        reportedTo: '2026-11-12',
      });
      expect(field('Reported from').value).toBe('2026-11-06');
      expect(ranges.getByRole('button', { name: 'Last 7 days' }).getAttribute('aria-pressed')).toBe(
        'true',
      );
      expect(ranges.getByRole('button', { name: 'Today' }).getAttribute('aria-pressed')).toBe(
        'false',
      );

      await userEvent.click(ranges.getByRole('button', { name: 'Today' }));
      expect(changes).toHaveBeenLastCalledWith({
        priority: 'HIGH',
        reportedFrom: '2026-11-12',
        reportedTo: '2026-11-12',
      });

      // Pressed again, it removes the days, as a toggle does.
      await userEvent.click(ranges.getByRole('button', { name: 'Today' }));
      expect(changes).toHaveBeenLastCalledWith({ priority: 'HIGH' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('clears every filter, and cannot clear when none is set', async () => {
    const changes = renderBar({ status: ['REPORTED', 'REOPENED'], reportedTo: '2026-11-05' });
    const clear = screen.getByRole('button', { name: 'Clear filters' });

    await userEvent.click(clear);

    expect(changes).toHaveBeenLastCalledWith({});
    expect(clear).toHaveProperty('disabled', true);
    // The disabled button cannot keep the focus, so it moves to the first filter (#78, T1).
    expect(document.activeElement).toBe(statusChoice('Reported'));
    expect(statusChoice('Reopened').checked).toBe(false);
  });

  it('keeps the rest of the bar working when the options cannot be loaded', async () => {
    const changes = renderBar(
      {},
      fakeApi({
        'GET /categories': () => json(500, { status: 500 }),
        'GET /departments': () => json(503, { status: 503 }),
      }),
    );

    expect(
      await within(field('Category')).findByRole('option', {
        name: 'The categories could not be loaded',
      }),
    ).toBeDefined();
    expect(field('Category')).toHaveProperty('disabled', true);
    expect(field('Department')).toHaveProperty('disabled', true);

    await userEvent.click(statusChoice('Validated'));
    expect(changes).toHaveBeenLastCalledWith({ status: ['VALIDATED'] });
  });

  it('shows a chosen category that is not among the active ones', async () => {
    renderBar({ categoryId: 'e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b' });
    await optionsLoaded();

    const category = field('Category') as HTMLSelectElement;
    expect(category.selectedOptions[0]?.textContent).toBe('Unknown category');
  });
});
