import { listIncidents, type components, type IncidentListQuery } from '@urbanpulse/api-client';
import { FINAL_STATUSES, OPEN_STATUSES } from '@urbanpulse/domain';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import type { ComponentProps } from 'react';

import { failed, ok, renderWithQueries } from '../test-utils';
import { MyReportsScreen } from './MyReportsScreen';

jest.mock('@urbanpulse/api-client', () => ({ listIncidents: jest.fn() }));
jest.mock('../session/SessionContext', () => ({ useSession: () => ({ api: {} }) }));
// Rendered without a navigator: coming back into focus is not part of these tests.
jest.mock('@react-navigation/native', () => ({ useFocusEffect: jest.fn() }));
// The iOS chevron would load its font, which Jest cannot.
jest.mock('@expo/vector-icons', () => ({ Ionicons: () => null }));

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];
type Navigation = ComponentProps<typeof MyReportsScreen>['navigation'];

const mockedListIncidents = jest.mocked(listIncidents);

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

/** The API's answer: `pages` of reports, served by the requested page number. */
function serve(pages: IncidentSummary[][]) {
  mockedListIncidents.mockImplementation(async (_api, query: IncidentListQuery = {}) => {
    const number = query.page ?? 0;
    const content = pages[number] ?? [];
    return ok({
      content,
      page: {
        number,
        size: 20,
        totalElements: pages.flat().length,
        totalPages: pages.length,
      },
    });
  });
}

async function renderScreen() {
  const navigation = { navigate: jest.fn() };
  const queryClient = await renderWithQueries(
    <MyReportsScreen
      navigation={navigation as unknown as Navigation}
      route={{ key: 'ReportList', name: 'ReportList' }}
    />,
  );
  return { navigation, queryClient };
}

/** A call that never answers, to keep a fetch running. */
function pending<T>(): Promise<T> {
  return new Promise(() => undefined);
}

beforeEach(() => {
  mockedListIncidents.mockReset();
});

describe('MyReportsScreen', () => {
  it('shows the open reports page by page and says when there are no more (#68 T3)', async () => {
    serve([
      [summary('a', 'Street light off', 'REPORTED'), summary('b', 'Pothole', 'IN_PROGRESS')],
      [summary('c', 'Broken bench', 'RESOLVED')],
    ]);
    await renderScreen();

    expect(await screen.findByText('Street light off')).toBeOnTheScreen();
    expect(screen.getByText('Pothole')).toBeOnTheScreen();
    expect(mockedListIncidents).toHaveBeenLastCalledWith(expect.anything(), {
      page: 0,
      size: 20,
      status: [...OPEN_STATUSES],
    });
    expect(screen.queryByText("That's all.")).not.toBeOnTheScreen();

    await fireEvent(screen.getByTestId('my-reports-list'), 'endReached');

    expect(await screen.findByText('Broken bench')).toBeOnTheScreen();
    expect(mockedListIncidents).toHaveBeenLastCalledWith(
      expect.anything(),
      expect.objectContaining({ page: 1 }),
    );
    expect(screen.getByText("That's all.")).toBeOnTheScreen();
  });

  it('switches to the closed reports (#68 T3)', async () => {
    mockedListIncidents.mockImplementation(async (_api, query: IncidentListQuery = {}) =>
      ok({
        content: query.status?.includes('CLOSED')
          ? [summary('z', 'Graffiti cleaned', 'CLOSED')]
          : [summary('a', 'Street light off', 'REPORTED')],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
      }),
    );
    await renderScreen();
    expect(await screen.findByText('Street light off')).toBeOnTheScreen();

    await fireEvent.press(screen.getByRole('tab', { name: 'Closed' }));

    expect(await screen.findByText('Graffiti cleaned')).toBeOnTheScreen();
    expect(screen.queryByText('Street light off')).not.toBeOnTheScreen();
    expect(mockedListIncidents).toHaveBeenLastCalledWith(
      expect.anything(),
      expect.objectContaining({ status: [...FINAL_STATUSES] }),
    );
    expect(screen.getByRole('tab', { name: 'Closed' })).toBeSelected();
  });

  it('opens the detail of the pressed report', async () => {
    serve([[summary('a', 'Street light off', 'REPORTED')]]);
    const { navigation } = await renderScreen();

    await fireEvent.press(await screen.findByRole('button', { name: /^Street light off/ }));

    expect(navigation.navigate).toHaveBeenCalledWith('ReportDetail', { incidentId: 'a' });
  });

  it('says so when there are no reports and offers to report one (#68 T1)', async () => {
    serve([]);
    const { navigation } = await renderScreen();

    expect(await screen.findByText('You have no open reports.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Report an incident' }));

    expect(navigation.navigate).toHaveBeenCalledWith('NewReport');
  });

  it('explains a failure and loads the reports on "Try again" (#68 T1)', async () => {
    mockedListIncidents.mockResolvedValue(failed({ kind: 'network' }));
    await renderScreen();

    expect(await screen.findByText(/UrbanPulse cannot be reached/)).toBeOnTheScreen();

    serve([[summary('a', 'Street light off', 'REPORTED')]]);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Street light off')).toBeOnTheScreen();
  });

  it('shows each report once when a new one shifts the pages', async () => {
    serve([
      [summary('a', 'Street light off', 'REPORTED'), summary('b', 'Pothole', 'REPORTED')],
      [summary('b', 'Pothole', 'REPORTED'), summary('c', 'Broken bench', 'REPORTED')],
    ]);
    await renderScreen();
    await screen.findByText('Pothole');

    await fireEvent(screen.getByTestId('my-reports-list'), 'endReached');

    expect(await screen.findByText('Broken bench')).toBeOnTheScreen();
    expect(screen.getAllByText('Pothole')).toHaveLength(1);
  });

  it('waits for "Try again" after a page fails, instead of retrying at every scroll', async () => {
    const pages = [
      [summary('a', 'Street light off', 'REPORTED')],
      [summary('c', 'Broken bench', 'REPORTED')],
    ];
    serve(pages);
    await renderScreen();
    await screen.findByText('Street light off');
    mockedListIncidents.mockResolvedValue(failed({ kind: 'network' }));

    await fireEvent(screen.getByTestId('my-reports-list'), 'endReached');
    expect(await screen.findByText('More reports could not be loaded.')).toBeOnTheScreen();
    const callsAfterFailure = mockedListIncidents.mock.calls.length;
    await fireEvent(screen.getByTestId('my-reports-list'), 'endReached');
    expect(mockedListIncidents).toHaveBeenCalledTimes(callsAfterFailure);

    serve(pages);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Broken bench')).toBeOnTheScreen();
  });

  it('does not ask for the next page while the list is refreshing', async () => {
    serve([
      [summary('a', 'Street light off', 'REPORTED')],
      [summary('c', 'Broken bench', 'REPORTED')],
    ]);
    const { queryClient } = await renderScreen();
    await screen.findByText('Street light off');
    mockedListIncidents.mockReset().mockImplementation(pending);

    // React Query tells the screen in timeouts of its own; the list reaches its end after that.
    await act(async () => {
      void queryClient.invalidateQueries();
      await waitFor(() => expect(queryClient.isFetching()).toBe(1));
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    await fireEvent(screen.getByTestId('my-reports-list'), 'endReached');

    // Only the refetch of page 0: asking for page 1 now would cancel it in silence.
    expect(mockedListIncidents).toHaveBeenCalledTimes(1);
    expect(mockedListIncidents).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ page: 0 }),
    );
  });

  it('keeps the loaded reports when a refresh fails, and says so', async () => {
    serve([[summary('a', 'Street light off', 'REPORTED')]]);
    const { queryClient } = await renderScreen();
    await screen.findByText('Street light off');
    mockedListIncidents.mockResolvedValue(
      failed({ kind: 'problem', status: 500, code: undefined }),
    );

    await queryClient.invalidateQueries();

    expect(await screen.findByText(/could not be refreshed/)).toBeOnTheScreen();
    expect(screen.getByText('Street light off')).toBeOnTheScreen();
  });
});
