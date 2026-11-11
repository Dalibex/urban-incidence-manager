import { screen, waitFor, within } from '@testing-library/react';
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
// T3, T12, T13); here a stand-in shows its markers as buttons, so a click selects as on the map.
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

const POTHOLE: IncidentPosition = {
  id: '9b2e4f1a-3c5d-4e6f-8a7b-1c2d3e4f5a6b',
  status: 'REPORTED',
  priority: 'HIGH',
  latitude: 36.7213,
  longitude: -4.4214,
};

const LAMP: IncidentPosition = {
  id: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
  status: 'ASSIGNED',
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
function mapApi(answers: Answer[], requests: URLSearchParams[] = []) {
  let call = 0;
  return fakeApi({
    'GET /incident-locations': (request) => {
      requests.push(new URL(request.url).searchParams);
      const answer = answers[Math.min(call, answers.length - 1)]!;
      call += 1;
      return answer();
    },
    [`GET /incidents/${POTHOLE.id}`]: () => json(200, POTHOLE_DETAIL),
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
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

  // T2: the filters of the list apply to the map, through the address.
  it('asks the API again with the new filters when one changes', async () => {
    const requests: URLSearchParams[] = [];
    renderApp(PATHS.map, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: mapApi([positions([POTHOLE])], requests),
    });
    await screen.findByText('1 incident on the map');

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

    // The button next to the message, besides the one of the filter bar.
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
});
