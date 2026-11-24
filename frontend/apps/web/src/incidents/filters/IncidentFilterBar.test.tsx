import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
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
const LIGHTS_TEAM = { id: 'd4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f7a', name: 'Lights team' };
// The ids sort in this order: LIGHTING, MAINTENANCE, PARKS, LIGHTS_TEAM, UNKNOWN.
const UNKNOWN = 'e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a8b';

// The API gives Parks before Street lighting, though the id of Street lighting sorts first.
const OPTIONS_API = {
  'GET /categories': () => json(200, [PARKS, LIGHTING]),
  'GET /departments': () => json(200, [MAINTENANCE, LIGHTS_TEAM]),
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

/** The button of the category or department filter; its name is `<Label>: <summary>`. */
function optionsButton(label: string): HTMLButtonElement {
  return screen.getByRole('button', { name: new RegExp(`^${label}:`) });
}

/** The list a filter opens: a group named like its label. */
function optionsList(label: string) {
  return within(screen.getByRole('group', { name: label }));
}

async function openOptions(label: string) {
  await userEvent.click(optionsButton(label));
  return optionsList(label);
}

async function optionsLoaded() {
  await waitFor(() => expect(optionsButton('Category').disabled).toBe(false));
  await waitFor(() => expect(optionsButton('Department').disabled).toBe(false));
}

describe('IncidentFilterBar', () => {
  it('offers every status and priority', async () => {
    renderBar();
    await optionsLoaded();

    const priorities = within(field('Priority') as HTMLSelectElement)
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
    expect(priorities).toEqual(['All priorities', 'High', 'Medium', 'Low']);
  });

  it('applies the priority at once and keeps the other filters', async () => {
    const changes = renderBar({ status: ['REPORTED'], categoryId: [LIGHTING.id] });
    await optionsLoaded();

    await userEvent.selectOptions(field('Priority'), 'High');

    expect(changes).toHaveBeenLastCalledWith({
      status: ['REPORTED'],
      categoryId: [LIGHTING.id],
      priority: 'HIGH',
    });
  });

  it('removes the priority when its "All" option is chosen', async () => {
    const changes = renderBar({ categoryId: [LIGHTING.id], priority: 'LOW' });
    await optionsLoaded();

    await userEvent.selectOptions(field('Priority'), 'All priorities');

    expect(changes).toHaveBeenLastCalledWith({ categoryId: [LIGHTING.id] });
  });

  // #265
  describe('the category and department filters', () => {
    it.each([['Category: All categories'], ['Department: All departments']])(
      'says "All" only when nothing is chosen: %s',
      async (name) => {
        renderBar();
        await optionsLoaded();

        expect(screen.getByRole('button', { name })).toBeDefined();
      },
    );

    it.each([
      ['one category', { categoryId: [LIGHTING.id] }, 'Category: Street lighting'],
      ['several categories', { categoryId: [LIGHTING.id, PARKS.id] }, 'Category: 2 categories'],
      ['one department', { departmentId: [MAINTENANCE.id] }, 'Department: Maintenance'],
      [
        'several departments',
        { departmentId: [MAINTENANCE.id, LIGHTS_TEAM.id] },
        'Department: 2 departments',
      ],
    ])('names the choice of %s, never "All"', async (_, filters, name) => {
      renderBar(filters);
      await optionsLoaded();

      expect(screen.getByRole('button', { name })).toBeDefined();
    });

    it('opens as a list of checkboxes in the order of the API, checked when chosen', async () => {
      renderBar({ departmentId: [LIGHTS_TEAM.id] });
      await optionsLoaded();
      const button = optionsButton('Department');
      expect(button.getAttribute('aria-expanded')).toBe('false');

      const list = await openOptions('Department');

      expect(button.getAttribute('aria-expanded')).toBe('true');
      const boxes = list.getAllByRole('checkbox') as HTMLInputElement[];
      expect(boxes.map((box) => [box.closest('label')?.textContent, box.checked])).toEqual([
        ['Maintenance', false],
        ['Lights team', true],
      ]);
    });

    it('applies each check at once, keeps the other filters and normalises the ids', async () => {
      const changes = renderBar({ status: ['REPORTED'], categoryId: [PARKS.id] });
      await optionsLoaded();

      const list = await openOptions('Category');
      await userEvent.click(list.getByRole('checkbox', { name: 'Street lighting' }));

      // Street lighting sorts before Parks by id, though it was chosen last.
      expect(changes).toHaveBeenLastCalledWith({
        status: ['REPORTED'],
        categoryId: [LIGHTING.id, PARKS.id],
      });
    });

    it('counts every option chosen, which is a filter and not the same as none', async () => {
      const changes = renderBar();
      await optionsLoaded();

      const list = await openOptions('Category');
      await userEvent.click(list.getByRole('checkbox', { name: 'Parks' }));
      await userEvent.click(list.getByRole('checkbox', { name: 'Street lighting' }));

      expect(changes).toHaveBeenLastCalledWith({ categoryId: [LIGHTING.id, PARKS.id] });
      expect(screen.getByRole('button', { name: 'Category: 2 categories' })).toBeDefined();
    });

    it('removes the filter when its last choice is unchecked', async () => {
      const changes = renderBar({ categoryId: [LIGHTING.id], priority: 'LOW' });
      await optionsLoaded();

      const list = await openOptions('Category');
      await userEvent.click(list.getByRole('checkbox', { name: 'Street lighting' }));

      expect(changes).toHaveBeenLastCalledWith({ priority: 'LOW' });
      expect(screen.getByRole('button', { name: 'Category: All categories' })).toBeDefined();
    });

    it('keeps the other choices when one is unchecked', async () => {
      const changes = renderBar({ departmentId: [MAINTENANCE.id, LIGHTS_TEAM.id] });
      await optionsLoaded();

      const list = await openOptions('Department');
      await userEvent.click(list.getByRole('checkbox', { name: 'Maintenance' }));

      expect(changes).toHaveBeenLastCalledWith({ departmentId: [LIGHTS_TEAM.id] });
    });

    it('shows a chosen id that is not among the options as unknown, and unchecking removes it', async () => {
      const changes = renderBar({ categoryId: [PARKS.id, UNKNOWN] });
      await optionsLoaded();

      const list = await openOptions('Category');
      const unknown = list.getByRole('checkbox', { name: 'Unknown category' }) as HTMLInputElement;
      expect(unknown.checked).toBe(true);
      await userEvent.click(unknown);

      expect(changes).toHaveBeenLastCalledWith({ categoryId: [PARKS.id] });
      expect(list.queryByRole('checkbox', { name: 'Unknown category' })).toBeNull();
    });

    it('numbers two unknown ids, so each can be told apart and unticked', async () => {
      const OTHER_UNKNOWN = 'f0e1d2c3-b4a5-4968-8776-655443322110';
      const changes = renderBar({ categoryId: [UNKNOWN, OTHER_UNKNOWN] });
      await optionsLoaded();

      const list = await openOptions('Category');
      expect(list.queryByRole('checkbox', { name: 'Unknown category' })).toBeNull();
      expect(list.getByRole('checkbox', { name: 'Unknown category 1' })).toBeDefined();
      // Numbered in the order of the ids, as the address keeps them: OTHER_UNKNOWN is second.
      await userEvent.click(list.getByRole('checkbox', { name: 'Unknown category 2' }));

      expect(changes).toHaveBeenLastCalledWith({ categoryId: [UNKNOWN] });
    });

    it('names an unknown department with its own noun', async () => {
      renderBar({ departmentId: [UNKNOWN] });
      await optionsLoaded();

      const list = await openOptions('Department');

      expect(list.getByRole('checkbox', { name: 'Unknown department' })).toBeDefined();
    });

    describe('closing the list', () => {
      it('closes with Escape and gives the focus back to the button', async () => {
        renderBar();
        await optionsLoaded();
        const list = await openOptions('Category');
        list.getByRole('checkbox', { name: 'Parks' }).focus();

        await userEvent.keyboard('{Escape}');

        expect(screen.queryByRole('group', { name: 'Category' })).toBeNull();
        expect(optionsButton('Category').getAttribute('aria-expanded')).toBe('false');
        expect(document.activeElement).toBe(optionsButton('Category'));
      });

      it('closes with a click outside', async () => {
        renderBar();
        await optionsLoaded();
        await openOptions('Category');

        await userEvent.click(document.body);

        expect(screen.queryByRole('group', { name: 'Category' })).toBeNull();
      });

      it('closes when the button is pressed again', async () => {
        renderBar();
        await optionsLoaded();
        await openOptions('Category');

        await userEvent.click(optionsButton('Category'));

        expect(screen.queryByRole('group', { name: 'Category' })).toBeNull();
      });

      it('closes when the focus leaves the field with Tab', async () => {
        renderBar();
        await optionsLoaded();
        const list = await openOptions('Category');
        list.getByRole('checkbox', { name: 'Street lighting' }).focus();

        await userEvent.tab();

        expect(screen.queryByRole('group', { name: 'Category' })).toBeNull();
      });

      it('stays open while the focus moves between its checkboxes', async () => {
        renderBar();
        await optionsLoaded();
        const list = await openOptions('Category');
        list.getByRole('checkbox', { name: 'Parks' }).focus();

        await userEvent.tab();

        expect(screen.getByRole('group', { name: 'Category' })).toBeDefined();
        expect(document.activeElement).toBe(
          list.getByRole('checkbox', { name: 'Street lighting' }),
        );
      });
    });

    describe('with the keyboard', () => {
      it('opens the list with Enter on the button, and Space on a checkbox chooses it', async () => {
        const changes = renderBar();
        await optionsLoaded();
        optionsButton('Category').focus();

        await userEvent.keyboard('{Enter}');
        expect(screen.getByRole('group', { name: 'Category' })).toBeDefined();
        await userEvent.tab();
        await userEvent.keyboard(' ');

        expect(changes).toHaveBeenLastCalledWith({ categoryId: [PARKS.id] });
      });

      it('opens the list with Space on the button', async () => {
        renderBar();
        await optionsLoaded();
        optionsButton('Category').focus();

        await userEvent.keyboard(' ');

        expect(screen.getByRole('group', { name: 'Category' })).toBeDefined();
      });
    });

    it('is disabled with its placeholder while the options load, and the bar keeps working', async () => {
      const changes = renderBar({}, () => new Promise<Response>(() => {}));

      expect(screen.getByRole('button', { name: 'Category: Loading categories…' })).toHaveProperty(
        'disabled',
        true,
      );
      expect(
        screen.getByRole('button', { name: 'Department: Loading departments…' }),
      ).toHaveProperty('disabled', true);
      await userEvent.click(statusChoice('Validated'));
      expect(changes).toHaveBeenLastCalledWith({ status: ['VALIDATED'] });
    });
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
    const changes = renderBar({
      status: ['REPORTED', 'REOPENED'],
      categoryId: [LIGHTING.id, PARKS.id],
      reportedTo: '2026-11-05',
    });
    await optionsLoaded();
    const clear = screen.getByRole('button', { name: 'Clear filters' });

    await userEvent.click(clear);

    expect(changes).toHaveBeenLastCalledWith({});
    expect(clear).toHaveProperty('disabled', true);
    // The disabled button cannot keep the focus, so it moves to the first filter (#78, T1).
    expect(document.activeElement).toBe(statusChoice('Reported'));
    expect(statusChoice('Reopened').checked).toBe(false);
    expect(screen.getByRole('button', { name: 'Category: All categories' })).toBeDefined();
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
      await screen.findByRole('button', { name: 'Category: The categories could not be loaded' }),
    ).toHaveProperty('disabled', true);
    expect(
      screen.getByRole('button', { name: 'Department: The departments could not be loaded' }),
    ).toHaveProperty('disabled', true);

    await userEvent.click(statusChoice('Validated'));
    expect(changes).toHaveBeenLastCalledWith({ status: ['VALIDATED'] });
  });
});
