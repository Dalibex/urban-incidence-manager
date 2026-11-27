import { act, cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MAP_REFRESH_INTERVAL_MS } from '../incidents/incident-queries';
import { incidentPath, PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';
import type { IncidentMapProps } from './IncidentMap';

type IncidentPosition = components['schemas']['IncidentPosition'];
type Incident = components['schemas']['IncidentResponse'];

// MapLibre draws with WebGL, which jsdom does not have. The real map is checked by hand (#76 T1,
// T3, T12, T13); here a stand-in shows its markers as buttons, so a click selects as on the map,
// each with the icon it would draw (#267).
vi.mock('./IncidentMap', async () => {
  const { iconKeyOf } = await import('./category-icons');
  return {
    IncidentMap: ({ positions, categories, onSelect }: IncidentMapProps) => (
      <div role="region" aria-label="Map stand-in">
        {positions.map((position) => (
          <button
            key={position.id}
            type="button"
            data-icon={iconKeyOf(position.categoryId, categories)}
            onClick={() => onSelect(position.id)}
          >
            Marker {position.id}
          </button>
        ))}
      </div>
    ),
  };
});

// The summary beside the map (#177) has its own tests (MapDashboard.acceptance.test.tsx); here its
// charts are left out, so Plotly is not loaded.
vi.mock('./StatisticsChart', () => ({ StatisticsChart: () => null }));

/** The counts of the summary beside the map, whatever the filters. */
const statistics = () =>
  json(200, { total: 0, byStatus: [], byPriority: [], byCategory: [], byDepartment: [] });

/** The latest incidents of the summary: none. */
const latest = () =>
  json(200, { content: [], page: { number: 0, size: 5, totalElements: 0, totalPages: 0 } });

const POTHOLE: IncidentPosition = {
  id: '9b2e4f1a-3c5d-4e6f-8a7b-1c2d3e4f5a6b',
  status: 'REPORTED',
  categoryId: null,
  priority: 'HIGH',
  latitude: 36.7213,
  longitude: -4.4214,
};

const LAMP: IncidentPosition = {
  id: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
  status: 'ASSIGNED',
  categoryId: null,
  priority: null,
  latitude: 36.7155,
  longitude: -4.4302,
};

const POTHOLE_DETAIL: Incident = {
  id: POTHOLE.id,
  title: 'Pothole on Calle Larios',
  description: 'A deep pothole in the middle of the street.',
  status: 'REPORTED',
  priority: 'HIGH',
  category: { id: 'c1', name: 'Pavement' },
  location: { latitude: POTHOLE.latitude, longitude: POTHOLE.longitude },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
  version: 0,
} as Incident;

type Answer = () => Response;

/** A fake API whose positions come from `answers`, one per call, the last one repeated. */
function mapApi(answers: Answer[], requests: URLSearchParams[] = [], categories: unknown[] = []) {
  let call = 0;
  return fakeApi({
    'GET /incident-locations': (request) => {
      requests.push(new URL(request.url).searchParams);
      const answer = answers[Math.min(call, answers.length - 1)]!;
      call += 1;
      return answer();
    },
    [`GET /incidents/${POTHOLE.id}`]: () => json(200, POTHOLE_DETAIL),
    'GET /categories': () => json(200, categories),
    'GET /departments': () => json(200, []),
    'GET /incident-statistics': statistics,
    'GET /incidents': latest,
  });
}

const positions =
  (content: IncidentPosition[], truncated = false): Answer =>
  () =>
    json(200, { content, truncated });

function markers(): string[] {
  return within(screen.getByRole('region', { name: 'Map stand-in' }))
    .queryAllByRole('button')
    .map((marker) => marker.textContent ?? '');
}

describe('incident map on the web (#196, test #76)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the positions the API returns, and how many', async () => {
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE, LAMP])]),
    });

    expect(await screen.findByText('2 incidents on the map')).toBeDefined();
    expect(markers()).toEqual([`Marker ${POTHOLE.id}`, `Marker ${LAMP.id}`]);
  });

  // #267: the map gets the categories, so each marker draws the icon of its own.
  it('gives each marker the icon of its category', async () => {
    const lighting = {
      id: 'c1',
      name: 'Street lighting',
      description: null,
      icon: 'STREET_LIGHTING',
    };
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([{ ...POTHOLE, categoryId: 'c1' }, LAMP])], [], [lighting]),
    });
    await screen.findByText('2 incidents on the map');

    await waitFor(() =>
      expect(
        within(screen.getByRole('region', { name: 'Map stand-in' }))
          .getAllByRole('button')
          .map((marker) => marker.dataset.icon),
      ).toEqual(['STREET_LIGHTING', 'GENERIC']),
    );
  });

  // T2: the filters of the list apply to the map, through the address.
  it('asks the API again with the new filters when one changes', async () => {
    const requests: URLSearchParams[] = [];
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE])], requests),
    });
    await screen.findByText('1 incident on the map');

    // The filters are folded behind a button (#177).
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Priority' }), 'HIGH');

    await waitFor(() => expect(requests.at(-1)?.getAll('priority')).toEqual(['HIGH']));
  });

  // T10
  it('shows an empty map with a message and a way to clear the filters, not an error', async () => {
    renderApp(`${PATHS.map}?status=CLOSED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([])]),
    });

    const message = await screen.findByText('No incidents match these filters.');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('region', { name: 'Map stand-in' })).toBeDefined();

    // The button next to the message, besides the one next to the folded filters.
    await userEvent.click(
      within(message.parentElement!).getByRole('button', { name: 'Clear filters' }),
    );
    expect(await screen.findByText('There are no incidents to show.')).toBeDefined();
  });

  // T11
  it('keeps the last markers with a warning when a refresh fails, and recovers on its own', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const failure: Answer = () => json(503, { status: 503 });
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      // The first refresh fails three times: the call and the two retries of a 5xx.
      fetch: mapApi([positions([POTHOLE]), failure, failure, failure, positions([POTHOLE, LAMP])]),
    });
    await screen.findByText('1 incident on the map');

    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);
    const warning = await screen.findByRole('alert');
    expect(warning.textContent).toContain('The map could not be refreshed.');
    expect(markers()).toEqual([`Marker ${POTHOLE.id}`]);

    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);
    expect(await screen.findByText('2 incidents on the map')).toBeDefined();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  // T14: a new incident appears without reloading the page.
  it('reads the positions again on its own', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE]), positions([POTHOLE, LAMP])]),
    });
    await screen.findByText('1 incident on the map');

    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);

    expect(await screen.findByText('2 incidents on the map')).toBeDefined();
  });

  it('says when the API left incidents out, so the user narrows the filters', async () => {
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE, LAMP], true)]),
    });

    expect(
      await screen.findByText(
        'The map shows the 2 most relevant incidents. Narrow the filters to see the rest.',
      ),
    ).toBeDefined();
  });

  it('opens the summary of a marker, with Manage leading to its detail', async () => {
    const router = renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE])]),
    });

    await userEvent.click(await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` }));

    const panel = await screen.findByRole('complementary', { name: POTHOLE_DETAIL.title });
    expect(within(panel).getByText('Pavement')).toBeDefined();
    expect(within(panel).getByText('High')).toBeDefined();
    await userEvent.click(
      within(panel).getByRole('link', { name: `Manage ${POTHOLE_DETAIL.title}` }),
    );
    expect(router.state.location.pathname).toBe(incidentPath(POTHOLE.id));
  });

  // #264: the detail opened from the map leads back to it, with the same filters and search.
  it.each(['MUNICIPAL_OPERATOR', 'TECHNICIAN'] as const)(
    'leads a %s from Manage back to the map with the same filters',
    async (role) => {
      const filtered = `${PATHS.map}?status=REPORTED&priority=HIGH&q=Larios`;
      const router = renderApp(filtered, { role, fetch: mapApi([positions([POTHOLE])]) });

      await userEvent.click(await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` }));
      const panel = await screen.findByRole('complementary', { name: POTHOLE_DETAIL.title });
      await userEvent.click(
        within(panel).getByRole('link', { name: `Manage ${POTHOLE_DETAIL.title}` }),
      );
      expect(
        await screen.findByRole('heading', { level: 1, name: POTHOLE_DETAIL.title }),
      ).toBeDefined();

      await userEvent.click(screen.getByRole('link', { name: 'Back to the map' }));
      const { pathname, search } = router.state.location;
      expect(pathname + search).toBe(filtered);
      expect(await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` })).toBeDefined();
    },
  );

  // Failure case 404: the incident left the user's scope (closed, or no longer assigned to a
  // technician) between a refresh of the map and the click on its marker.
  it('says the incident is no longer visible, and keeps the map, when its summary answers 404', async () => {
    renderApp(PATHS.map, {
      role: 'TECHNICIAN',
      fetch: fakeApi({
        'GET /incident-locations': positions([POTHOLE, LAMP]),
        [`GET /incidents/${POTHOLE.id}`]: () =>
          json(404, { status: 404, code: 'NOT_FOUND', title: 'Not Found' }),
        'GET /categories': () => json(200, []),
        'GET /departments': () => json(200, []),
        'GET /incident-statistics': statistics,
        'GET /incidents': latest,
      }),
    });

    await userEvent.click(await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` }));

    const panel = await screen.findByRole('complementary', { name: 'Incident not found' });
    expect(within(panel).getByText('It does not exist, or you cannot see it.')).toBeDefined();
    expect(within(panel).queryByRole('button', { name: 'Try again' })).toBeNull();
    expect(within(panel).queryByRole('link')).toBeNull();
    expect(markers()).toEqual([`Marker ${POTHOLE.id}`, `Marker ${LAMP.id}`]);
  });

  // T17
  it('reaches the summary and Manage with the keyboard alone, through the list', async () => {
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE, LAMP])]),
    });
    const list = await screen.findByText(/List of the incidents on the map/);
    list.focus();
    await userEvent.keyboard('{Enter}');

    const item = screen.getByRole('button', { name: /Reported\s*High priority/ });
    item.focus();
    await userEvent.keyboard('{Enter}');

    const heading = await screen.findByRole('heading', { level: 2, name: POTHOLE_DETAIL.title });
    await waitFor(() => expect(document.activeElement).toBe(heading));

    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('complementary')).toBeNull();
    expect(document.activeElement).toBe(item);
  });

  it('opens from the menu of an operator on the incidents that can still change', async () => {
    const operator = renderApp(PATHS.home, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: fakeApi({
        'GET /incidents': () =>
          json(200, {
            content: [],
            page: { number: 0, size: 20, totalElements: 0, totalPages: 0 },
          }),
        'GET /incident-locations': positions([]),
        'GET /categories': () => json(200, []),
        'GET /departments': () => json(200, []),
        'GET /incident-statistics': statistics,
      }),
    });
    await userEvent.click(await screen.findByRole('link', { name: 'Map' }));
    expect(new URLSearchParams(operator.state.location.search).getAll('status')).toEqual([
      'REPORTED',
      'VALIDATED',
      'ASSIGNED',
      'IN_PROGRESS',
      'RESOLVED',
      'REOPENED',
    ]);
  });

  // #265
  it('narrows the map and its summary to several categories, and keeps them in the address', async () => {
    const parks = { id: 'c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f', name: 'Parks', description: null };
    const lighting = {
      id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
      name: 'Street lighting',
      description: null,
    };
    const requests: URLSearchParams[] = [];
    const statisticsRequests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.map}?status=REPORTED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: fakeApi({
        'GET /incident-locations': (request) => {
          requests.push(new URL(request.url).searchParams);
          return positions([POTHOLE])();
        },
        'GET /categories': () => json(200, [parks, lighting]),
        'GET /departments': () => json(200, []),
        'GET /incident-statistics': (request) => {
          statisticsRequests.push(new URL(request.url).searchParams);
          return statistics();
        },
        'GET /incidents': latest,
      }),
    });
    await screen.findByText('1 incident on the map');
    await userEvent.click(screen.getByRole('button', { name: /^Filters/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Category: All categories' }));

    const list = within(screen.getByRole('group', { name: 'Category' }));
    await userEvent.click(list.getByRole('checkbox', { name: 'Parks' }));
    await userEvent.click(list.getByRole('checkbox', { name: 'Street lighting' }));

    // The ids come in the same order whatever the order they were chosen in.
    const both = [lighting.id, parks.id];
    await waitFor(() => expect(requests.at(-1)?.getAll('categoryId')).toEqual(both));
    await waitFor(() => expect(statisticsRequests.at(-1)?.getAll('categoryId')).toEqual(both));
    expect(new URLSearchParams(router.state.location.search).getAll('categoryId')).toEqual(both);
  });

  describe('search (#266)', () => {
    it('searches the typed text on submit, on the map and on its summary', async () => {
      const requests: URLSearchParams[] = [];
      const statisticsRequests: URLSearchParams[] = [];
      const router = renderApp(PATHS.map, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: fakeApi({
          'GET /incident-locations': (request) => {
            requests.push(new URL(request.url).searchParams);
            return positions([POTHOLE])();
          },
          'GET /categories': () => json(200, []),
          'GET /departments': () => json(200, []),
          'GET /incident-statistics': (request) => {
            statisticsRequests.push(new URL(request.url).searchParams);
            return statistics();
          },
          'GET /incidents': latest,
        }),
      });
      await screen.findByText('1 incident on the map');

      // Typing alone asks nothing: the search runs on submit.
      await userEvent.type(screen.getByRole('searchbox', { name: 'Search' }), '  Farola rota ');
      expect(requests.every((params) => !params.has('q'))).toBe(true);
      await userEvent.keyboard('{Enter}');

      await waitFor(() => expect(requests.at(-1)?.get('q')).toBe('Farola rota'));
      await waitFor(() => expect(statisticsRequests.at(-1)?.get('q')).toBe('Farola rota'));
      expect(new URLSearchParams(router.state.location.search).get('q')).toBe('Farola rota');
      // The folded filters name it, so nobody forgets the map is narrowed.
      expect(screen.getByText('Search: “Farola rota”')).toBeDefined();
    });

    it('keeps the search box at hand while the filters are folded', async () => {
      renderApp(`${PATHS.map}?q=farola`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([POTHOLE])]),
      });

      const box = await screen.findByRole('searchbox', { name: 'Search' });
      expect((box as HTMLInputElement).value).toBe('farola');
      expect(
        screen.getByRole('button', { name: 'Filters (1)' }).getAttribute('aria-expanded'),
      ).toBe('false');
    });

    it('does not send a text that is too short, and says why', async () => {
      const requests: URLSearchParams[] = [];
      renderApp(PATHS.map, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([POTHOLE])], requests),
      });
      await screen.findByText('1 incident on the map');
      const box = screen.getByRole('searchbox', { name: 'Search' });

      await userEvent.type(box, 'ab');
      await userEvent.click(screen.getByRole('button', { name: 'Search' }));

      expect(box.getAttribute('aria-invalid')).toBe('true');
      // Announced, since the focus is on the button, and linked to the box for later.
      const reason = screen.getByRole('alert');
      expect(reason.textContent).toBe('Type at least 3 characters.');
      expect(reason.id).toBe(box.getAttribute('aria-describedby'));
      expect(requests.every((params) => !params.has('q'))).toBe(true);

      // Fixing the text clears the message once it is searched.
      await userEvent.type(box, 'c{Enter}');
      await waitFor(() => expect(requests.at(-1)?.get('q')).toBe('abc'));
      expect(box.getAttribute('aria-invalid')).toBeNull();
      expect(screen.queryByText('Type at least 3 characters.')).toBeNull();
    });

    it('removes the search when the box is emptied and submitted', async () => {
      const requests: URLSearchParams[] = [];
      renderApp(`${PATHS.map}?q=farola`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([POTHOLE])], requests),
      });
      const box = await screen.findByRole('searchbox', { name: 'Search' });

      await userEvent.clear(box);
      await userEvent.keyboard('{Enter}');

      await waitFor(() => expect(requests.at(-1)?.has('q')).toBe(false));
    });

    // Escape and the ✕ of a search box empty it without a submit.
    it('stops searching when the box is emptied without a submit, keeping the focus', async () => {
      const requests: URLSearchParams[] = [];
      const router = renderApp(`${PATHS.map}?q=farola`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([POTHOLE])], requests),
      });
      const box = await screen.findByRole('searchbox', { name: 'Search' });

      await userEvent.clear(box);

      await waitFor(() => expect(requests.at(-1)?.has('q')).toBe(false));
      expect(new URLSearchParams(router.state.location.search).has('q')).toBe(false);
      expect(document.activeElement).toBe(box);
    });

    it('offers to clear a search that finds nothing, and clears the box too', async () => {
      renderApp(`${PATHS.map}?q=nothing`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([])]),
      });

      const message = await screen.findByText('No incidents match these filters.');
      await userEvent.click(
        within(message.parentElement!).getByRole('button', { name: 'Clear filters' }),
      );

      expect(await screen.findByText('There are no incidents to show.')).toBeDefined();
      expect((screen.getByRole('searchbox', { name: 'Search' }) as HTMLInputElement).value).toBe(
        '',
      );
    });

    it('also clears a text that was never searched, and its message', async () => {
      renderApp(`${PATHS.map}?status=REPORTED`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([POTHOLE])]),
      });
      const box = await screen.findByRole('searchbox', { name: 'Search' });
      await userEvent.type(box, 'ab{Enter}');
      expect(screen.getByRole('alert').textContent).toBe('Type at least 3 characters.');

      await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

      const cleared = screen.getByRole('searchbox', { name: 'Search' }) as HTMLInputElement;
      expect(cleared.value).toBe('');
      expect(screen.queryByText('Type at least 3 characters.')).toBeNull();
    });

    it('clears it too from the Clear filters of the open filter bar', async () => {
      renderApp(`${PATHS.map}?status=REPORTED`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([POTHOLE])]),
      });
      const box = await screen.findByRole('searchbox', { name: 'Search' });
      await userEvent.type(box, 'ab{Enter}');
      await userEvent.click(screen.getByRole('button', { name: 'Filters (1)' }));

      // With the bar open, its own button is the only Clear filters.
      await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

      const cleared = screen.getByRole('searchbox', { name: 'Search' }) as HTMLInputElement;
      expect(cleared.value).toBe('');
      expect(screen.queryByText('Type at least 3 characters.')).toBeNull();
    });

    // A search is typed for the moment; the filters a list remembers (#263) never hold it.
    it('is not remembered with the filters of the map', async () => {
      const router = renderApp(`${PATHS.map}?priority=HIGH&q=farola`, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: mapApi([positions([])]),
      });
      await screen.findByText('No incidents match these filters.');

      await act(() => router.navigate('/elsewhere'));
      await userEvent.click(screen.getByRole('link', { name: 'Map' }));

      await screen.findByText('No incidents match these filters.');
      expect(router.state.location.search).toBe('?priority=HIGH');
    });
  });

  // #263, AC1
  it('reopens from the menu with the filters last used on it, also none', async () => {
    const router = renderApp(`${PATHS.map}?status=CLOSED&priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([])]),
    });
    await screen.findByText('No incidents match these filters.');

    await act(() => router.navigate('/elsewhere'));
    await userEvent.click(screen.getByRole('link', { name: 'Map' }));
    await screen.findByText('No incidents match these filters.');
    expect(router.state.location.search).toBe('?status=CLOSED&priority=HIGH');

    await userEvent.click(screen.getAllByRole('button', { name: 'Clear filters' })[0]!);
    await act(() => router.navigate('/elsewhere'));
    await userEvent.click(screen.getByRole('link', { name: 'Map' }));
    await screen.findByRole('region', { name: 'Map stand-in' });
    expect(router.state.location.search).toBe('');
  });

  // #263, AC2
  it('gives another user of the browser their own filters, or the defaults', async () => {
    const first = renderApp(`${PATHS.map}?priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([])]),
    });
    await screen.findByText('No incidents match these filters.');
    await act(() => first.navigate('/elsewhere'));
    cleanup();

    const second = renderApp('/elsewhere', {
      role: 'MUNICIPAL_OPERATOR',
      userId: 'user-2',
      fetch: mapApi([positions([])]),
    });
    await userEvent.click(screen.getByRole('link', { name: 'Map' }));

    await screen.findByRole('region', { name: 'Map stand-in' });
    expect(new URLSearchParams(second.state.location.search).has('priority')).toBe(false);
    expect(new URLSearchParams(second.state.location.search).getAll('status')).toContain(
      'REPORTED',
    );
  });
});
