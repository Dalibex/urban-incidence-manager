import { getIncident, listIncidentStatusChanges, type components } from '@urbanpulse/api-client';
import { act, fireEvent, screen, within } from '@testing-library/react-native';
import { SKELETON_DELAY_MS } from '@urbanpulse/ui';
import type { ComponentProps } from 'react';

import { failed, ok, renderWithQueries } from '../test-utils';
import { IncidentDetailScreen } from './IncidentDetailScreen';

jest.mock('@urbanpulse/api-client', () => ({
  getIncident: jest.fn(),
  listIncidentStatusChanges: jest.fn(),
}));
jest.mock('../session/SessionContext', () => ({ useSession: () => ({ api: {} }) }));

type Incident = components['schemas']['IncidentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];
type Props = ComponentProps<typeof IncidentDetailScreen>;

const mockedGetIncident = jest.mocked(getIncident);
const mockedListStatusChanges = jest.mocked(listIncidentStatusChanges);

// What the API returns to a citizen: no priority, version, actor or visibleToCitizen.
const INCIDENT: Incident = {
  id: 'a',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'VALIDATED',
  category: { id: 'c', name: 'Street lighting' },
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: 'Calle Larios 12',
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T11:00:00Z',
  closedAt: null,
};

function change(
  id: string,
  previousStatus: StatusChange['previousStatus'],
  newStatus: StatusChange['newStatus'],
  reason: string | null = null,
): StatusChange {
  return { id, instant: '2026-11-02T10:00:00Z', previousStatus, newStatus, reason };
}

async function renderScreen(navigation = { navigate: jest.fn() }) {
  return renderWithQueries(
    <IncidentDetailScreen
      navigation={navigation as unknown as Props['navigation']}
      route={{ key: 'ReportDetail', name: 'ReportDetail', params: { incidentId: 'a' } }}
    />,
  );
}

beforeEach(() => {
  mockedGetIncident.mockReset().mockResolvedValue(ok(INCIDENT));
  mockedListStatusChanges.mockReset().mockResolvedValue(ok([]));
});

describe('IncidentDetailScreen', () => {
  it('shows the data of the report and where it is', async () => {
    await renderScreen();

    expect(await screen.findByText('Street light off')).toBeOnTheScreen();
    expect(screen.getByText('Validated')).toBeOnTheScreen();
    expect(screen.getByText('Street lighting')).toBeOnTheScreen();
    expect(screen.getByText('Calle Larios 12')).toBeOnTheScreen();
    expect(screen.getByText('36.72130, -4.42140')).toBeOnTheScreen();
    expect(screen.getByText('± 13 m')).toBeOnTheScreen();
  });

  it('shows the history the API returns, oldest first, with its messages (#68 T4)', async () => {
    mockedGetIncident.mockResolvedValue(ok({ ...INCIDENT, status: 'REJECTED' }));
    mockedListStatusChanges.mockResolvedValue(
      ok([
        change('1', null, 'REPORTED'),
        change('2', 'REPORTED', 'REPORTED', 'Which lamp post is it? Send its number.'),
        change('3', 'REPORTED', 'REJECTED', 'It is the same as another report.'),
      ]),
    );
    await renderScreen();

    const timeline = await screen.findByTestId('history-timeline');
    expect(
      within(timeline)
        .getAllByText(/^(Reported|Information requested|Rejected)$/)
        .map((entry) => entry.props.children),
    ).toEqual(['Reported', 'Information requested', 'Rejected']);
    expect(within(timeline).getByText('Message from the city council')).toBeOnTheScreen();
    expect(within(timeline).getByText('Which lamp post is it? Send its number.')).toBeOnTheScreen();
    expect(within(timeline).getByText('Reason')).toBeOnTheScreen();
    expect(within(timeline).getByText('It is the same as another report.')).toBeOnTheScreen();
  });

  it('shows "Not available" for the place data that is not known (#68 T5)', async () => {
    mockedGetIncident.mockResolvedValue(
      ok({
        ...INCIDENT,
        location: { ...INCIDENT.location, address: null, neighborhood: null, district: null },
      }),
    );
    await renderScreen();

    await screen.findByText('Street light off');
    expect(screen.getAllByText('Not available')).toHaveLength(3);
    expect(screen.getByText('36.72130, -4.42140')).toBeOnTheScreen();
  });

  it('shows only a not found message for an incident the API does not return (#68 T6)', async () => {
    mockedGetIncident.mockResolvedValue(failed({ kind: 'problem', status: 404, code: undefined }));
    await renderScreen();

    expect(await screen.findByText(/This report was not found/)).toBeOnTheScreen();
    expect(screen.queryByText('Street light off')).not.toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeOnTheScreen();
  });

  // #174
  it('draws the shape of the report while it loads', async () => {
    jest.useFakeTimers();
    try {
      mockedGetIncident.mockReturnValue(new Promise(() => undefined));
      await renderScreen();

      expect(screen.getByRole('progressbar', { name: 'Loading the report' })).toBeOnTheScreen();
      await act(async () => {
        jest.advanceTimersByTime(SKELETON_DELAY_MS);
      });
      expect(within(screen.getByTestId('skeleton')).getByText('Location')).toBeOnTheScreen();
    } finally {
      jest.useRealTimers();
    }
  });

  // #174
  it('keeps the report when only its history fails, and offers to load it again', async () => {
    mockedListStatusChanges.mockResolvedValue(
      failed({ kind: 'problem', status: 500, code: undefined }),
    );
    await renderScreen();

    expect(await screen.findByText(/The history could not be loaded/)).toBeOnTheScreen();
    expect(screen.getByText('Street light off')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();
  });

  it('offers to try again when the report cannot be loaded (#68 T1)', async () => {
    mockedGetIncident.mockResolvedValue(failed({ kind: 'problem', status: 500, code: undefined }));
    await renderScreen();

    expect(await screen.findByText(/The report could not be loaded/)).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();
  });

  it('keeps the loaded report on screen when a refresh fails', async () => {
    const queryClient = await renderScreen();
    await screen.findByText('Street light off');
    mockedGetIncident.mockResolvedValue(failed({ kind: 'network' }));

    await queryClient.invalidateQueries();

    expect(await screen.findByText(/could not be refreshed/)).toBeOnTheScreen();
    expect(screen.getByText('Street light off')).toBeOnTheScreen();
  });

  it('offers to add photos while the report waits for review (#193)', async () => {
    mockedGetIncident.mockResolvedValue(ok({ ...INCIDENT, status: 'REPORTED' }));
    const navigation = { navigate: jest.fn() };
    await renderScreen(navigation);

    await fireEvent.press(await screen.findByRole('button', { name: 'Add photos' }));

    expect(navigation.navigate).toHaveBeenCalledWith('AddPhotos', { incidentId: 'a' });
  });

  it('does not offer photos once the report left REPORTED, as the API refuses them', async () => {
    await renderScreen();

    expect(await screen.findByText('Street light off')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Add photos' })).not.toBeOnTheScreen();
  });
});
