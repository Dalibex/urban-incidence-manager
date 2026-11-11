import { screen, waitFor, within } from '@testing-library/react';
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

/** A fake API whose statistics come from `answers`, one per call, the last one repeated. */
function dashboardApi(
  answers: Answer[],
  requests: { locations: URLSearchParams[]; statistics: URLSearchParams[] } = {
    locations: [],
    statistics: [],
  },
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
    const requests = { locations: [] as URLSearchParams[], statistics: [] as URLSearchParams[] };
    renderApp(`${PATHS.map}?status=REPORTED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: dashboardApi([counts(STATISTICS)], requests),
    });
    await within(await screen.findByRole('region', { name: 'Summary' })).findAllByTestId('chart');

    await userEvent.click(screen.getByRole('button', { name: 'Filters (1)' }));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Priority' }), 'HIGH');

    await waitFor(() => expect(requests.statistics.at(-1)?.getAll('priority')).toEqual(['HIGH']));
    expect(requests.statistics.at(-1)?.toString()).toBe(requests.locations.at(-1)?.toString());
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
});
