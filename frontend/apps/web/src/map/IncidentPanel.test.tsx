import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MAP_REFRESH_INTERVAL_MS } from '../incidents/incident-queries';
import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';
import type { IncidentMapProps } from './IncidentMap';

type Incident = components['schemas']['IncidentResponse'];

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
vi.mock('./StatisticsChart', () => ({ StatisticsChart: () => null }));

const POTHOLE: Incident = {
  id: '9b2e4f1a-3c5d-4e6f-8a7b-1c2d3e4f5a6b',
  title: 'Pothole on Calle Larios',
  description: 'A deep pothole in the middle of the street.',
  status: 'VALIDATED',
  priority: 'HIGH',
  category: { id: 'c1', name: 'Pavement' },
  location: { latitude: 36.7213, longitude: -4.4214 },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
  version: 1,
} as Incident;

/** A fake API whose incident is the one `server.incident` holds at the moment of each read. */
function mapApi(server: { incident: Incident }) {
  return fakeApi({
    'GET /incident-locations': () =>
      json(200, {
        content: [
          {
            id: POTHOLE.id,
            status: server.incident.status,
            priority: 'HIGH',
            latitude: 36.7213,
            longitude: -4.4214,
          },
        ],
        truncated: false,
      }),
    [`GET /incidents/${POTHOLE.id}`]: () => json(200, server.incident),
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
    'GET /incident-statistics': () =>
      json(200, { total: 0, byStatus: [], byPriority: [], byCategory: [], byDepartment: [] }),
    'GET /incidents': () =>
      json(200, { content: [], page: { number: 0, size: 5, totalElements: 0, totalPages: 0 } }),
  });
}

describe('summary of the incident selected on the map (#287)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the new status once the map refreshes after the incident changed on the server', async () => {
    // Arrange
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const server = { incident: POTHOLE };
    renderApp(PATHS.map, { role: 'MUNICIPAL_OPERATOR', fetch: mapApi(server) });
    await userEvent.click(await screen.findByRole('button', { name: `Marker ${POTHOLE.id}` }));
    const panel = await screen.findByRole('complementary', { name: POTHOLE.title });
    expect(within(panel).getByText('Validated')).toBeDefined();
    server.incident = { ...POTHOLE, status: 'ASSIGNED', version: 2 };

    // Act
    await vi.advanceTimersByTimeAsync(MAP_REFRESH_INTERVAL_MS);

    // Assert
    expect(await within(panel).findByText('Assigned')).toBeDefined();
  });
});
