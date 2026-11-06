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
    expect(names('Status')).toEqual([
      'All statuses',
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
    const changes = renderBar({ status: 'REPORTED' });
    await optionsLoaded();

    await userEvent.selectOptions(field('Category'), 'Street lighting');
    await userEvent.selectOptions(field('Priority'), 'High');
    await userEvent.selectOptions(field('Department'), 'Maintenance');

    expect(changes).toHaveBeenLastCalledWith({
      status: 'REPORTED',
      categoryId: LIGHTING.id,
      priority: 'HIGH',
      departmentId: MAINTENANCE.id,
    });
  });

  it('removes a filter when its "All" option is chosen', async () => {
    const changes = renderBar({ status: 'ASSIGNED', priority: 'LOW' });

    await userEvent.selectOptions(field('Status'), 'All statuses');

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

  it('clears every filter, and cannot clear when none is set', async () => {
    const changes = renderBar({ status: 'REPORTED', reportedTo: '2026-11-05' });
    const clear = screen.getByRole('button', { name: 'Clear filters' });

    await userEvent.click(clear);

    expect(changes).toHaveBeenLastCalledWith({});
    expect(clear).toHaveProperty('disabled', true);
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

    await userEvent.selectOptions(field('Status'), 'Validated');
    expect(changes).toHaveBeenLastCalledWith({ status: 'VALIDATED' });
  });

  it('shows a chosen category that is not among the active ones', async () => {
    renderBar({ categoryId: 'e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b' });
    await optionsLoaded();

    const category = field('Category') as HTMLSelectElement;
    expect(category.selectedOptions[0]?.textContent).toBe('Unknown category');
  });
});
