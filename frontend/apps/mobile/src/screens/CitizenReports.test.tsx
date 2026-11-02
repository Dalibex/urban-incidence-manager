import {
  getIncident,
  listIncidents,
  listIncidentStatusChanges,
  type components,
  type IncidentListQuery,
} from '@urbanpulse/api-client';
import { fireEvent, screen, within } from '@testing-library/react-native';
import type { ComponentProps } from 'react';

import { ok, renderWithQueries } from '../test-utils';
import { IncidentDetailScreen } from './IncidentDetailScreen';
import { MyReportsScreen } from './MyReportsScreen';

/**
 * Acceptance cases of #68 that the developer tests of #45 leave open: an unknown accuracy (T5), a
 * history whose reasons the citizen does not receive (T4), and which statuses each filter asks for
 * when the citizen switches it (T3). They go through the screens with a mocked API, like the tests
 * next to them.
 */

jest.mock('@urbanpulse/api-client', () => ({
  listIncidents: jest.fn(),
  getIncident: jest.fn(),
  listIncidentStatusChanges: jest.fn(),
}));
jest.mock('../session/SessionContext', () => ({ useSession: () => ({ api: {} }) }));
jest.mock('@react-navigation/native', () => ({ useFocusEffect: jest.fn() }));
jest.mock('@expo/vector-icons', () => ({ Ionicons: () => null }));

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];
type Incident = components['schemas']['IncidentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];
type ListNavigation = ComponentProps<typeof MyReportsScreen>['navigation'];
type DetailNavigation = ComponentProps<typeof IncidentDetailScreen>['navigation'];

const mockedListIncidents = jest.mocked(listIncidents);
const mockedGetIncident = jest.mocked(getIncident);
const mockedListStatusChanges = jest.mocked(listIncidentStatusChanges);

/** Text that means a missing value leaked to the screen instead of "Not available". */
const LEAKED_VALUE = /\b(null|undefined|NaN)\b/;

// What the API returns to a citizen: no priority, version, actor or visibleToCitizen.
const FOUNTAIN: Incident = {
  id: 'e1000000-0000-4000-8000-000000000014',
  title: 'Leaking fountain',
  description: 'Water is leaking from the base of the fountain.',
  status: 'REOPENED',
  category: { id: '16fff6f7-e8ab-4841-ae3e-9dff80314c01', name: 'Urban furniture' },
  location: {
    latitude: 36.721,
    longitude: -4.4208,
    accuracy: 8,
    address: 'Plaza de la Constitución',
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-10-20T11:00:00Z',
  updatedAt: '2026-10-23T09:15:00Z',
  closedAt: null,
};

function change(
  id: string,
  instant: string,
  previousStatus: StatusChange['previousStatus'],
  newStatus: StatusChange['newStatus'],
): StatusChange {
  // The citizen never receives the reason of a resolution or a reopening (ADR-0020): it is null.
  return { id, instant, previousStatus, newStatus, reason: null };
}

function summary(id: string, title: string, status: IncidentSummary['status']): IncidentSummary {
  return {
    id,
    title,
    status,
    category: null,
    reportedAt: '2026-11-02T09:58:31Z',
    updatedAt: '2026-11-02T09:58:31Z',
  };
}

function screenText(): string {
  return screen
    .getAllByText(/./)
    .map((node) => String(node.props.children))
    .join(' | ');
}

async function renderDetail() {
  return renderWithQueries(
    <IncidentDetailScreen
      navigation={{} as DetailNavigation}
      route={{ key: 'ReportDetail', name: 'ReportDetail', params: { incidentId: FOUNTAIN.id } }}
    />,
  );
}

async function renderList() {
  return renderWithQueries(
    <MyReportsScreen
      navigation={{ navigate: jest.fn() } as unknown as ListNavigation}
      route={{ key: 'ReportList', name: 'ReportList' }}
    />,
  );
}

beforeEach(() => {
  mockedListIncidents.mockReset();
  mockedGetIncident.mockReset().mockResolvedValue(ok(FOUNTAIN));
  mockedListStatusChanges.mockReset().mockResolvedValue(ok([]));
});

describe('citizen reports (#68)', () => {
  // T5: the contract also sends a null accuracy when the device did not report it.
  it('shows an unknown accuracy, address, neighbourhood and district as "Not available"', async () => {
    mockedGetIncident.mockResolvedValue(
      ok({
        ...FOUNTAIN,
        location: {
          ...FOUNTAIN.location,
          accuracy: null,
          address: null,
          neighborhood: null,
          district: null,
        },
      }),
    );
    await renderDetail();

    await screen.findByText('Leaking fountain');
    expect(screen.getAllByText('Not available')).toHaveLength(4);
    expect(screenText()).not.toMatch(LEAKED_VALUE);
  });

  // T4: every entry is shown, and an entry without a reason shows no empty reason.
  it('shows the whole visible history without empty reasons when the API hides them', async () => {
    mockedListStatusChanges.mockResolvedValue(
      ok([
        change('1', '2026-10-20T11:00:00Z', null, 'REPORTED'),
        change('2', '2026-10-20T12:00:00Z', 'REPORTED', 'VALIDATED'),
        change('3', '2026-10-20T12:20:00Z', 'VALIDATED', 'ASSIGNED'),
        change('4', '2026-10-21T08:30:00Z', 'ASSIGNED', 'IN_PROGRESS'),
        change('5', '2026-10-21T12:00:00Z', 'IN_PROGRESS', 'RESOLVED'),
        change('6', '2026-10-23T09:15:00Z', 'RESOLVED', 'REOPENED'),
      ]),
    );
    await renderDetail();

    const timeline = await screen.findByTestId('history-timeline');
    expect(
      within(timeline)
        .getAllByText(/^(Reported|Validated|Assigned|Work started|Resolved|Reopened)$/)
        .map((entry) => entry.props.children),
    ).toEqual(['Reported', 'Validated', 'Assigned', 'Work started', 'Resolved', 'Reopened']);
    expect(within(timeline).queryByText('Reason')).not.toBeOnTheScreen();
    expect(within(timeline).queryByText('Message from the city council')).not.toBeOnTheScreen();
    expect(screenText()).not.toMatch(LEAKED_VALUE);
  });

  // T3: "Open" holds what can still change, a resolved report too; "Closed" only the final ones.
  it('asks each filter for its statuses from the first page, after scrolling the other', async () => {
    mockedListIncidents.mockImplementation(async (_api, query: IncidentListQuery = {}) => {
      const closed = query.status?.includes('CLOSED') ?? false;
      const page = query.page ?? 0;
      return ok({
        content: closed
          ? [summary('z', 'Graffiti cleaned', 'CLOSED')]
          : [summary(`open-${page}`, `Open report ${page + 1}`, 'RESOLVED')],
        page: { number: page, size: 20, totalElements: closed ? 1 : 2, totalPages: closed ? 1 : 2 },
      });
    });
    await renderList();
    await screen.findByText('Open report 1');
    await fireEvent(screen.getByTestId('my-reports-list'), 'endReached');
    await screen.findByText('Open report 2');

    await fireEvent.press(screen.getByRole('tab', { name: 'Closed' }));
    await screen.findByText('Graffiti cleaned');

    const [openQuery] = mockedListIncidents.mock.calls[0]!.slice(1) as [IncidentListQuery];
    const [closedQuery] = mockedListIncidents.mock.lastCall!.slice(1) as [IncidentListQuery];
    expect([...(openQuery.status ?? [])].sort()).toEqual([
      'ASSIGNED',
      'IN_PROGRESS',
      'REOPENED',
      'REPORTED',
      'RESOLVED',
      'VALIDATED',
    ]);
    expect([...(closedQuery.status ?? [])].sort()).toEqual(['CLOSED', 'REJECTED']);
    expect(closedQuery.page ?? 0).toBe(0);
    expect(screen.queryByText('Open report 1')).not.toBeOnTheScreen();
    expect(screen.queryByText('Open report 2')).not.toBeOnTheScreen();
  });
});
