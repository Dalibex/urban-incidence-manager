import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MAP_REFRESH_INTERVAL_MS } from '../incidents/incident-queries';
import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';
import { chartDescription } from './dashboard-data';
import type { IncidentMapProps } from './IncidentMap';
import type { StatisticsChartProps } from './StatisticsChart';

type IncidentPosition = components['schemas']['IncidentPosition'];
type Incident = components['schemas']['IncidentResponse'];
type Statistics = components['schemas']['IncidentStatisticsResponse'];

// MapLibre draws with WebGL, which jsdom does not have: a stand-in shows the markers as buttons.
vi.mock('./IncidentMap', () => ({
  IncidentMap: ({ positions, onSelect }: IncidentMapProps) => (
    <div role="region" aria-label="Map stand-in">
      {positions.map((position) => (
        <button key={position.id} type="button" onClick={() => onSelect(position.id)}>
          Marker {position.id}
        </button>
      ))}
    </div>
  ),
}));

// Plotly draws SVG that means nothing here. The stand-in shows what the real chart gives a screen
// reader, its description, so a test reads the figures of each chart. The real chart is checked
// by hand (#258 T5), and its fallbacks in LazyStatisticsChart.test.tsx.
vi.mock('./StatisticsChart', () => ({
  StatisticsChart: ({ chart }: StatisticsChartProps) => (
    <p data-testid="chart">{chartDescription(chart)}</p>
  ),
}));

const POTHOLE: IncidentPosition = {
  id: '9b2e4f1a-3c5d-4e6f-8a7b-1c2d3e4f5a6b',
  status: 'REPORTED',
  priority: 'HIGH',
  latitude: 36.7213,
  longitude: -4.4214,
};

const POTHOLE_DETAIL = {
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

const STATISTICS: Statistics = {
  total: 3,
  byStatus: [
    { status: 'REPORTED', count: 2 },
    { status: 'VALIDATED', count: 0 },
    { status: 'ASSIGNED', count: 1 },
  ],
  byPriority: [
    { priority: 'HIGH', count: 1 },
    { priority: 'MEDIUM', count: 0 },
    { priority: 'LOW', count: 0 },
    { priority: null, count: 2 },
  ],
  byCategory: [
    { categoryId: 'c1', name: 'Pavement', count: 2 },
    { categoryId: null, name: null, count: 1 },
  ],
  byDepartment: [
    { departmentId: 'd1', name: 'Public works', count: 1 },
    { departmentId: null, name: null, count: 2 },
  ],
};

const NOTHING: Statistics = {
  total: 0,
  byStatus: [{ status: 'CLOSED', count: 0 }],
  byPriority: [
    { priority: 'HIGH', count: 0 },
    { priority: 'MEDIUM', count: 0 },
    { priority: 'LOW', count: 0 },
    { priority: null, count: 0 },
  ],
  byCategory: [],
  byDepartment: [],
};

type Answer = () => Response;

const counts =
  (statistics: Statistics): Answer =>
  () =>
    json(200, statistics);

const failure: Answer = () => json(503, { status: 503 });

/** The latest incidents reported, newest first, as `listIncidents` gives them. */
const LATEST = [
  {
    id: POTHOLE.id,
    title: POTHOLE_DETAIL.title,
    status: 'REPORTED',
    priority: 'HIGH',
    category: { id: 'c1', name: 'Pavement' },
    reportedAt: '2026-11-11T11:55:00Z',
    updatedAt: '2026-11-11T11:55:00Z',
  },
  {
    id: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
    title: 'Street light off on Alameda',
    status: 'ASSIGNED',
    priority: null,
    category: null,
    reportedAt: '2026-11-10T09:00:00Z',
    updatedAt: '2026-11-10T09:00:00Z',
  },
];

const latestPage: Answer = () =>
  json(200, {
    content: LATEST,
    page: { number: 0, size: 5, totalElements: LATEST.length, totalPages: 1 },
  });

interface Requests {
  locations: URLSearchParams[];
  statistics: URLSearchParams[];
  latest: URLSearchParams[];
}

/** A fake API whose statistics come from `answers`, one per call, the last one repeated. */
function dashboardApi(
  answers: Answer[],
  requests: Requests = { locations: [], statistics: [], latest: [] },
  latest: Answer = latestPage,
) {
  let call = 0;
  return fakeApi({
    'GET /incident-locations': (request) => {
      requests.locations.push(new URL(request.url).searchParams);
      return json(200, { content: [POTHOLE], truncated: false });
    },
    'GET /incident-statistics': (request) => {
      requests.statistics.push(new URL(request.url).searchParams);
      const answer = answers[Math.min(call, answers.length - 1)]!;
      call += 1;
      return answer();
    },
    'GET /incidents': (request) => {
      requests.latest.push(new URL(request.url).searchParams);
      return latest();
    },
    [`GET /incidents/${POTHOLE.id}`]: () => json(200, POTHOLE_DETAIL),
    'GET /categories': () => json(200, [{ id: 'c1', name: 'Pavement', description: null }]),
    'GET /departments': () => json(200, [{ id: 'd1', name: 'Public works' }]),
  });
}

function summary() {
  return screen.getByRole('region', { name: 'Summary' });
}

describe('staff dashboard on the map (#177, test #258)', () => {
  afterEach(() => {
    vi.useRealTimers();
    // What a test folded stays in this browser for the next one otherwise.
    window.localStorage.clear();
  });

  // T5, T3: the figures and the three charts, the groups without a value named.
  it('shows the total, the priorities and the charts beside the map', async () => {
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: dashboardApi([counts(STATISTICS)]) });

    const dashboard = await screen.findByRole('region', { name: 'Summary' });
    const charts = await within(dashboard).findAllByTestId('chart');
    expect(await screen.findByRole('region', { name: 'Map stand-in' })).toBeDefined();

    const figures = Array.from(dashboard.querySelectorAll('dt')).map(
      (term) => `${term.textContent} ${term.nextElementSibling?.textContent}`,
    );
    expect(figures).toEqual(['Incidents 3', 'High 1', 'Medium 0', 'Low 0', 'No priority 2']);
    expect(charts.map((chart) => chart.textContent)).toEqual([
      'Incidents by status: Reported 2, Validated 0, Assigned 1.',
      'Incidents by category: Pavement 2, Not classified 1.',
      'Incidents by department: Public works 1, Not assigned 2.',
    ]);
  });

  // T6: the dashboard asks with the filters of the map.
  it('asks for the counts with the same filters as the map, also when one changes', async () => {
    const requests: Requests = { locations: [], statistics: [], latest: [] };
    renderApp(`${PATHS.map}?status=REPORTED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS)], requests),
    });
    await within(await screen.findByRole('region', { name: 'Summary' })).findAllByTestId('chart');

    await userEvent.click(screen.getByRole('button', { name: 'Filters (1)' }));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Priority' }), 'HIGH');

    await waitFor(() => expect(requests.statistics.at(-1)?.getAll('priority')).toEqual(['HIGH']));
    expect(requests.statistics.at(-1)?.toString()).toBe(requests.locations.at(-1)?.toString());
    // The latest list uses the same filters, on the first page of the newest.
    await waitFor(() => expect(requests.latest.at(-1)?.getAll('priority')).toEqual(['HIGH']));
    expect(requests.latest.at(-1)?.get('size')).toBe('5');
  });

  // T7: the filters fold, and a line says which ones apply.
  it('folds the filters behind a button that says how many apply, and which', async () => {
    renderApp(`${PATHS.map}?status=REPORTED&categoryId=7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS)]),
    });

    const toggle = await screen.findByRole('button', { name: 'Filters (2)' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('search', { name: 'Filter incidents' })).toBeNull();
    expect(screen.getByText('Status: Reported · Category: selected')).toBeDefined();

    await userEvent.click(toggle);

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('search', { name: 'Filter incidents' })).toBeDefined();
  });

  // T7: clearing while folded keeps the keyboard in the filters.
  it('clears the folded filters and leaves the focus on the button that opens them', async () => {
    const router = renderApp(`${PATHS.map}?priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS)]),
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Clear filters' }));

    expect(router.state.location.search).toBe('');
    const toggle = screen.getByRole('button', { name: 'Filters' });
    expect(document.activeElement).toBe(toggle);
    expect(screen.getByText('No filters: every incident you can see.')).toBeDefined();
  });

  // T8: the selected incident takes the place of the summary, and gives it back.
  it('shows the selected incident in place of the summary, and brings the summary back', async () => {
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: dashboardApi([counts(STATISTICS)]) });
    await within(await screen.findByRole('region', { name: 'Summary' })).findAllByTestId('chart');

    const marker = await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` });
    await userEvent.click(marker);

    const panel = await screen.findByRole('complementary', { name: POTHOLE_DETAIL.title });
    expect(screen.queryByRole('region', { name: 'Summary' })).toBeNull();

    await userEvent.click(within(panel).getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('complementary')).toBeNull();
    expect(summary()).toBeDefined();
    // The focus goes back to what opened the panel, not to the top of the page.
    expect(document.activeElement).toBe(marker);
  });

  // The summary does not say "with these filters" when there are none.
  it('says there are no incidents to count when nothing matches without filters', async () => {
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: dashboardApi([counts(NOTHING)]) });

    expect(
      await within(await screen.findByRole('region', { name: 'Summary' })).findByText(
        'There are no incidents to count.',
      ),
    ).toBeDefined();
  });

  // T16
  it('says there is nothing to count, without an error, when no incident matches', async () => {
    renderApp(`${PATHS.map}?status=CLOSED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(NOTHING)]),
    });

    expect(
      await within(await screen.findByRole('region', { name: 'Summary' })).findByText(
        'Nothing to count with these filters.',
      ),
    ).toBeDefined();
    expect(within(summary()).queryByTestId('chart')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  // T17
  it('offers to try again when the counts cannot be loaded, and keeps the map', async () => {
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      // The call and the two retries of a 5xx fail; trying again succeeds.
      fetch: dashboardApi([failure, failure, failure, counts(STATISTICS)]),
    });

    const alert = await within(await screen.findByRole('region', { name: 'Summary' })).findByRole(
      'alert',
    );
    expect(alert.textContent).toContain('The summary could not be loaded.');
    expect(screen.getByRole('button', { name: `Marker ${POTHOLE.id}` })).toBeDefined();

    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await within(summary()).findAllByTestId('chart')).toHaveLength(3);
    expect(within(summary()).queryByRole('alert')).toBeNull();
  });

  // T18: a failed refresh keeps the last counts, never zeros, and recovers on its own.
  it('keeps the last counts with a warning when a refresh fails, and recovers on its own', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS), failure, failure, failure, counts(NOTHING)]),
    });
    await within(await screen.findByRole('region', { name: 'Summary' })).findAllByTestId('chart');

    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);
    const warning = await within(summary()).findByRole('alert');
    expect(warning.textContent).toContain('The summary could not be refreshed.');
    expect(within(summary()).getByText('Incidents').nextElementSibling?.textContent).toBe('3');

    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);
    // The map is opened without filters, so the new counts say there is nothing at all.
    expect(await within(summary()).findByText('There are no incidents to count.')).toBeDefined();
    expect(within(summary()).queryByRole('alert')).toBeNull();
  });

  // The latest incidents reported, newest first, each opening its summary like a marker.
  it('lists the latest incidents, opens one, and gives the focus back to it on close', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-11T12:00:00Z') });
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: dashboardApi([counts(STATISTICS)]) });

    const latest = within(
      await within(await screen.findByRole('region', { name: 'Summary' })).findByRole('list'),
    );
    const items = await latest.findAllByRole('button');
    expect(items.map((item) => item.textContent)).toEqual([
      `${POTHOLE_DETAIL.title}Reported5 minutes ago`,
      'Street light off on AlamedaAssigned27 hours ago',
    ]);

    items[0]!.focus();
    await userEvent.keyboard('{Enter}');
    const panel = await screen.findByRole('complementary', { name: POTHOLE_DETAIL.title });
    // Longer than the cache keeps unused data (5 minutes): the summary stays mounted while hidden,
    // so its list and the button that opened the incident are still there.
    await vi.advanceTimersByTimeAsync(6 * 60_000);
    await userEvent.click(within(panel).getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(document.activeElement).toBe(items[0]));
  });

  it('keeps the counts when only the latest list fails, and offers to try it again', async () => {
    let calls = 0;
    // The call and the two retries of a 5xx fail; trying again succeeds.
    const flaky: Answer = () => (calls++ < 3 ? failure() : latestPage());
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS)], undefined, flaky),
    });

    const dashboard = await screen.findByRole('region', { name: 'Summary' });
    const alert = await within(dashboard).findByRole('alert');
    expect(alert.textContent).toContain('The latest incidents could not be loaded.');
    expect(within(dashboard).getAllByTestId('chart')).toHaveLength(3);

    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await within(dashboard).findByText(POTHOLE_DETAIL.title)).toBeDefined();
    expect(within(dashboard).queryByRole('alert')).toBeNull();
  });

  it('keeps the latest list with a note of its time when a refresh of it fails', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let calls = 0;
    const thenFailing: Answer = () => (calls++ === 0 ? latestPage() : failure());
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS)], undefined, thenFailing),
    });
    const dashboard = await screen.findByRole('region', { name: 'Summary' });
    await within(dashboard).findByText(POTHOLE_DETAIL.title);

    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);

    expect(await within(dashboard).findByText(/Not refreshed: as of/)).toBeDefined();
    expect(within(dashboard).getByText(POTHOLE_DETAIL.title)).toBeDefined();
  });

  // Each part folds, and stays folded for the same user when the map is opened again.
  it('remembers the parts the user folded, for that user only', async () => {
    const api = () => dashboardApi([counts(STATISTICS)]);
    const opened = async (userId?: string) => {
      cleanup();
      renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: api(), userId });
      await within(await screen.findByRole('region', { name: 'Summary' })).findAllByTestId('chart');
    };
    await opened();

    await userEvent.click(screen.getByText('By category'));
    expect(screen.getByText('By category').closest('details')?.open).toBe(false);

    await opened();
    expect(screen.getByText('By category').closest('details')?.open).toBe(false);
    expect(screen.getByText('By status').closest('details')?.open).toBe(true);

    // Another user of the same browser has their own.
    await opened('user-2');
    expect(screen.getByText('By category').closest('details')?.open).toBe(true);
  });

  it('still folds when the browser refuses its storage, and simply does not remember', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Blocked', 'SecurityError');
    });
    try {
      renderApp(PATHS.map, {
        role: 'MUNICIPAL_OPERATOR',
        fetch: dashboardApi([counts(STATISTICS)]),
      });
      await within(await screen.findByRole('region', { name: 'Summary' })).findAllByTestId('chart');

      await userEvent.click(screen.getByText('By category'));

      expect(screen.getByText('By category').closest('details')?.open).toBe(false);
    } finally {
      vi.restoreAllMocks();
    }
  });

  // The key is on the map, not in the summary, so it stays while an incident is open.
  it('keeps the key of the map while an incident is open, and remembers it', async () => {
    const api = () => dashboardApi([counts(STATISTICS)]);
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: api() });
    await userEvent.click(await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` }));
    const panel = await screen.findByRole('complementary', { name: POTHOLE_DETAIL.title });

    const key = () => screen.getByText('Map key').closest('details');
    expect(key()?.open).toBe(false);
    await userEvent.click(screen.getByText('Map key'));
    expect(key()?.open).toBe(true);

    await userEvent.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(key()?.open).toBe(true);

    cleanup();
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: api() });
    await screen.findByRole('region', { name: 'Summary' });
    expect(key()?.open).toBe(true);
  });

  // A large count never spills out of its column, and its exact value is still there.
  it('writes a large count short, with the exact one for a screen reader', async () => {
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts({ ...STATISTICS, total: 12_345 })]),
    });

    const total = (
      await within(await screen.findByRole('region', { name: 'Summary' })).findByText('Incidents')
    ).nextElementSibling!;
    expect(
      within(total as HTMLElement)
        .getByText('12.3K')
        .getAttribute('aria-hidden'),
    ).toBe('true');
    expect(within(total as HTMLElement).getByText('12,345')).toBeDefined();
    // The exact number on hover.
    expect(within(total as HTMLElement).getByTitle('12,345')).toBeDefined();
  });
});
