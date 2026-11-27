import {
  getAttachmentContent,
  getIncident,
  listIncidentAttachments,
  listIncidentClassificationChanges,
  listIncidentStatusChanges,
  type components,
} from '@urbanpulse/api-client';
import { act, fireEvent, screen, within } from '@testing-library/react-native';
import { SKELETON_DELAY_MS } from '@urbanpulse/ui';
import type { ComponentProps } from 'react';

import { PENDING_ADDRESS_CHECKS, PENDING_ADDRESS_INTERVAL_MS } from '../incidents/incident-queries';
import { failed, ok, renderWithQueries } from '../test-utils';
import { IncidentDetailScreen } from './IncidentDetailScreen';

jest.mock('@urbanpulse/api-client', () => ({
  getAttachmentContent: jest.fn(),
  getIncident: jest.fn(),
  listIncidentAttachments: jest.fn(),
  listIncidentClassificationChanges: jest.fn(),
  listIncidentStatusChanges: jest.fn(),
}));
// A Blob cannot be read here; what the conversion does is tested in its own file.
jest.mock('../attachments/blob-uri', () => ({
  blobToDataUri: jest.fn(async () => 'data:image/jpeg;base64,AAAA'),
}));
jest.mock('../session/SessionContext', () => ({ useSession: () => ({ api: {} }) }));

type Incident = components['schemas']['IncidentResponse'];
type Attachment = components['schemas']['AttachmentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];
type Props = ComponentProps<typeof IncidentDetailScreen>;

const mockedGetIncident = jest.mocked(getIncident);
const mockedListStatusChanges = jest.mocked(listIncidentStatusChanges);
const mockedListAttachments = jest.mocked(listIncidentAttachments);
const mockedGetContent = jest.mocked(getAttachmentContent);

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
    addressStatus: 'AVAILABLE' as const,
    addressSource: {
      name: 'OpenStreetMap',
      attribution: '© OpenStreetMap contributors',
      url: 'https://www.openstreetmap.org/copyright',
    },
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T11:00:00Z',
  closedAt: null,
};

function photo(id: string): Attachment {
  return {
    id,
    fileName: `${id}.jpg`,
    contentType: 'image/jpeg',
    size: 1000,
    uploadedAt: '2026-11-02T10:00:12Z',
  };
}

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
  mockedListAttachments.mockReset().mockResolvedValue(ok([]));
  mockedGetContent.mockReset().mockResolvedValue(ok(new Blob(['x'])));
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

  it("shows the city council instead of a person, and the report as the citizen's (#170 T2)", async () => {
    mockedListStatusChanges.mockResolvedValue(
      ok([
        change('1', null, 'REPORTED'),
        // The API sends a citizen no actor; even if one came, no staff name is shown.
        {
          ...change('2', 'REPORTED', 'VALIDATED'),
          actor: { id: 'o', name: 'Lucía Martín', role: 'MUNICIPAL_OPERATOR' },
        },
      ]),
    );
    await renderScreen();

    const timeline = await screen.findByTestId('history-timeline');
    expect(within(timeline).getByText(/· You$/)).toBeOnTheScreen();
    expect(within(timeline).getByText(/· City council$/)).toBeOnTheScreen();
    expect(within(timeline).queryByText(/Lucía Martín/)).toBeNull();
  });

  it('keeps one order for entries of the same instant (#170 T11)', async () => {
    mockedListStatusChanges.mockResolvedValue(
      ok([change('9', 'REPORTED', 'VALIDATED'), change('1', 'REPORTED', 'REPORTED', 'Which?')]),
    );
    await renderScreen();

    const timeline = await screen.findByTestId('history-timeline');
    expect(
      within(timeline)
        .getAllByText(/^(Validated|Information requested)$/)
        .map((entry) => entry.props.children),
    ).toEqual(['Information requested', 'Validated']);
  });

  it('never asks for the classification changes, which are for staff (#170 T14)', async () => {
    mockedListStatusChanges.mockResolvedValue(ok([change('1', null, 'REPORTED')]));
    await renderScreen();

    await screen.findByTestId('history-timeline');
    expect(jest.mocked(listIncidentClassificationChanges)).not.toHaveBeenCalled();
    expect(screen.queryByText('Classification changed')).toBeNull();
  });

  it('shows "Not available" for the place data that is not known (#68 T5)', async () => {
    mockedGetIncident.mockResolvedValue(
      ok({
        ...INCIDENT,
        location: {
          ...INCIDENT.location,
          address: null,
          addressStatus: 'UNAVAILABLE',
          addressSource: null,
          neighborhood: null,
          district: null,
        },
      }),
    );
    await renderScreen();

    await screen.findByText('Street light off');
    expect(screen.getAllByText('Not available')).toHaveLength(3);
    expect(screen.getByText('36.72130, -4.42140')).toBeOnTheScreen();
  });

  // #378, T4: the address resolved from the position, with the attribution OpenStreetMap asks for.
  it('shows the address with its source', async () => {
    await renderScreen();

    await screen.findByText('Street light off');
    expect(screen.getByText('Calle Larios 12')).toBeOnTheScreen();
    expect(screen.getByText('© OpenStreetMap contributors')).toBeOnTheScreen();
    expect(
      screen.getByLabelText('Address: Calle Larios 12. © OpenStreetMap contributors'),
    ).toBeOnTheScreen();
  });

  // #378, T4: without an address the citizen is told why, and no source is shown.
  it.each([
    ['PENDING', 'Looking up the address…'],
    ['OUTSIDE_AREA', 'No address: the position is outside Málaga'],
  ] as const)('says why there is no address when it is %s', async (addressStatus, text) => {
    mockedGetIncident.mockResolvedValue(
      ok({
        ...INCIDENT,
        location: { ...INCIDENT.location, address: null, addressStatus, addressSource: null },
      }),
    );
    await renderScreen();

    await screen.findByText('Street light off');
    expect(screen.getByText(text)).toBeOnTheScreen();
    expect(screen.queryByText('© OpenStreetMap contributors')).not.toBeOnTheScreen();
    expect(screen.queryByText('Calle Larios 12')).not.toBeOnTheScreen();
  });

  // #436: the worker resolves the address some seconds after the report.
  describe('while the address is being looked up', () => {
    const PENDING_ADDRESS: Incident = {
      ...INCIDENT,
      location: {
        ...INCIDENT.location,
        address: null,
        addressStatus: 'PENDING',
        addressSource: null,
      },
    };

    beforeEach(() => {
      jest.useFakeTimers();
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    async function advance(ms: number) {
      await act(async () => {
        await jest.advanceTimersByTimeAsync(ms);
      });
    }

    it('shows the address once the worker resolved it, and stops reading', async () => {
      mockedGetIncident.mockResolvedValue(ok(PENDING_ADDRESS));
      await renderScreen();
      expect(await screen.findByText('Looking up the address…')).toBeOnTheScreen();

      mockedGetIncident.mockResolvedValue(ok(INCIDENT));
      await advance(PENDING_ADDRESS_INTERVAL_MS);

      expect(screen.getByText('Calle Larios 12')).toBeOnTheScreen();
      expect(screen.queryByText('Looking up the address…')).not.toBeOnTheScreen();
      const reads = mockedGetIncident.mock.calls.length;
      await advance(PENDING_ADDRESS_INTERVAL_MS * 4);
      expect(mockedGetIncident).toHaveBeenCalledTimes(reads);
    });

    const NOT_ARRIVED = 'The address has not arrived yet. Pull down to check again.';

    it('stops on its own after two minutes, and says the address has not arrived', async () => {
      mockedGetIncident.mockResolvedValue(ok(PENDING_ADDRESS));
      await renderScreen();
      await screen.findByText('Looking up the address…');

      await advance(PENDING_ADDRESS_INTERVAL_MS * PENDING_ADDRESS_CHECKS);
      expect(mockedGetIncident).toHaveBeenCalledTimes(PENDING_ADDRESS_CHECKS + 1);
      expect(screen.getByText(NOT_ARRIVED)).toBeOnTheScreen();
      expect(screen.queryByText('Looking up the address…')).not.toBeOnTheScreen();

      await advance(PENDING_ADDRESS_INTERVAL_MS * 4);
      expect(mockedGetIncident).toHaveBeenCalledTimes(PENDING_ADDRESS_CHECKS + 1);
    });

    it('shows the address when a pull to refresh finds it after the wait', async () => {
      mockedGetIncident.mockResolvedValue(ok(PENDING_ADDRESS));
      await renderScreen();
      await screen.findByText('Looking up the address…');
      await advance(PENDING_ADDRESS_INTERVAL_MS * PENDING_ADDRESS_CHECKS);
      expect(screen.getByText(NOT_ARRIVED)).toBeOnTheScreen();

      mockedGetIncident.mockResolvedValue(ok(INCIDENT));
      await act(async () => {
        await screen.getByTestId('report-detail').props.refreshControl.props.onRefresh();
      });

      expect(screen.getByText('Calle Larios 12')).toBeOnTheScreen();
      expect(screen.queryByText(NOT_ARRIVED)).not.toBeOnTheScreen();
    });

    it('keeps the report when a read fails, and stops reading', async () => {
      mockedGetIncident.mockResolvedValue(ok(PENDING_ADDRESS));
      await renderScreen();
      await screen.findByText('Looking up the address…');

      mockedGetIncident.mockResolvedValue(failed({ kind: 'network' }));
      await advance(PENDING_ADDRESS_INTERVAL_MS);

      expect(
        screen.getByText('The report could not be refreshed. This is the last version loaded.'),
      ).toBeOnTheScreen();
      // It no longer reads the report, so it does not say the address is on its way.
      expect(screen.getByText(NOT_ARRIVED)).toBeOnTheScreen();
      expect(screen.queryByText('Looking up the address…')).not.toBeOnTheScreen();
      const reads = mockedGetIncident.mock.calls.length;
      await advance(PENDING_ADDRESS_INTERVAL_MS * 4);
      expect(mockedGetIncident).toHaveBeenCalledTimes(reads);
    });
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

  // #279, T1: the photos the API returns, nothing else.
  it('shows the photos of the report and asks only for those of this report (#279 T1)', async () => {
    mockedListAttachments.mockResolvedValue(ok([photo('p1'), photo('p2')]));
    await renderScreen();

    expect(await screen.findByLabelText('Photo 1 of 2')).toBeOnTheScreen();
    expect(screen.getByLabelText('Photo 2 of 2')).toBeOnTheScreen();
    expect(screen.getByText('Photos')).toBeOnTheScreen();
    expect(mockedListAttachments).toHaveBeenCalledWith(expect.anything(), 'a');
    expect(mockedGetContent.mock.calls.map(([, id]) => id).sort()).toEqual(['p1', 'p2']);
  });

  // #279, T3
  it('shows no gallery and no error when the report has no photos (#279 T3)', async () => {
    await renderScreen();

    await screen.findByText('Street light off');
    await screen.findByText('History');
    expect(mockedListAttachments).toHaveBeenCalled();
    expect(screen.queryByText('Photos')).not.toBeOnTheScreen();
    expect(screen.queryByText(/photos could not be loaded/)).not.toBeOnTheScreen();
    expect(mockedGetContent).not.toHaveBeenCalled();
  });

  // #279, T8
  it('keeps the report when the photos fail, and loads them again on retry (#279 T8)', async () => {
    mockedListAttachments.mockResolvedValue(
      failed({ kind: 'problem', status: 500, code: undefined }),
    );
    await renderScreen();

    expect(await screen.findByText(/The photos could not be loaded/)).toBeOnTheScreen();
    expect(screen.getByText('Street light off')).toBeOnTheScreen();
    expect(screen.getByText('Calle Larios 12')).toBeOnTheScreen();

    mockedListAttachments.mockResolvedValue(ok([photo('p1')]));
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByLabelText('Photo 1 of 1')).toBeOnTheScreen();
    expect(screen.queryByText(/The photos could not be loaded/)).not.toBeOnTheScreen();
  });

  // #279, T7
  it('shows the photos that load when one fails, and tries only that one again (#279 T7)', async () => {
    mockedListAttachments.mockResolvedValue(ok([photo('p1'), photo('p2')]));
    mockedGetContent.mockImplementation(async (_api, id) =>
      id === 'p2' ? failed({ kind: 'problem', status: 500, code: undefined }) : ok(new Blob(['x'])),
    );
    await renderScreen();

    expect(await screen.findByLabelText('Photo 1 of 2')).toBeOnTheScreen();
    expect(await screen.findByText('This photo could not be loaded.')).toBeOnTheScreen();
    expect(screen.queryByLabelText('Photo 2 of 2')).not.toBeOnTheScreen();
    expect(screen.getByText('Street light off')).toBeOnTheScreen();

    mockedGetContent.mockClear().mockResolvedValue(ok(new Blob(['x'])));
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByLabelText('Photo 2 of 2')).toBeOnTheScreen();
    expect(mockedGetContent).toHaveBeenCalledTimes(1);
    expect(mockedGetContent).toHaveBeenCalledWith(expect.anything(), 'p2');
  });

  // #279, T12: the photo is gone, so trying again would get the same answer.
  it('says a photo is no longer available, without offering to try again (#279 T12)', async () => {
    mockedListAttachments.mockResolvedValue(ok([photo('p1'), photo('p2')]));
    mockedGetContent.mockImplementation(async (_api, id) =>
      id === 'p2' ? failed({ kind: 'problem', status: 404, code: undefined }) : ok(new Blob(['x'])),
    );
    await renderScreen();

    expect(await screen.findByText('This photo is no longer available.')).toBeOnTheScreen();
    expect(screen.getByLabelText('Photo 1 of 2')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeOnTheScreen();
  });

  // #279, T3: no header while the list loads, so the layout does not jump.
  it('shows no Photos header while the list loads (#279 T3)', async () => {
    mockedListAttachments.mockReturnValue(new Promise(() => undefined));
    await renderScreen();

    await screen.findByText('History');
    expect(screen.queryByText('Photos')).not.toBeOnTheScreen();
    expect(screen.queryByLabelText('Loading the photos')).not.toBeOnTheScreen();
  });

  it('keeps the photos on screen when a refresh of the list fails, and says so', async () => {
    mockedListAttachments.mockResolvedValue(ok([photo('p1')]));
    const queryClient = await renderScreen();
    expect(await screen.findByLabelText('Photo 1 of 1')).toBeOnTheScreen();
    mockedListAttachments.mockResolvedValue(failed({ kind: 'network' }));

    await queryClient.invalidateQueries();

    expect(await screen.findByText(/could not be refreshed/)).toBeOnTheScreen();
    expect(screen.getByLabelText('Photo 1 of 1')).toBeOnTheScreen();
    expect(screen.queryByText(/The photos could not be loaded/)).not.toBeOnTheScreen();
  });

  it('loads again a photo that failed when the citizen pulls to refresh', async () => {
    mockedListAttachments.mockResolvedValue(ok([photo('p1')]));
    mockedGetContent.mockResolvedValue(failed({ kind: 'problem', status: 500, code: undefined }));
    await renderScreen();
    expect(await screen.findByText('This photo could not be loaded.')).toBeOnTheScreen();

    mockedGetContent.mockResolvedValue(ok(new Blob(['x'])));
    await act(async () => {
      await screen.getByTestId('report-detail').props.refreshControl.props.onRefresh();
    });

    expect(await screen.findByLabelText('Photo 1 of 1')).toBeOnTheScreen();
  });

  it('does not offer photos once the report left REPORTED, as the API refuses them', async () => {
    await renderScreen();

    expect(await screen.findByText('Street light off')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Add photos' })).not.toBeOnTheScreen();
  });
});
