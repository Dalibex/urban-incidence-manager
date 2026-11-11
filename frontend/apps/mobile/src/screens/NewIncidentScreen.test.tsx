import type { BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import { createIncident, listCategories, type CreateIncidentResult } from '@urbanpulse/api-client';
import { act, fireEvent, screen, userEvent, waitFor } from '@testing-library/react-native';
import { Alert } from 'react-native';

import { MAP_LOAD_FAILED } from '../components/BaseMap';
import { PLACED_BY_HAND } from '../components/LocationIndicator';
import { useIncidentLocation, type UseIncidentLocationResult } from '../hooks/useIncidentLocation';
import { categoryKeys, incidentKeys } from '../incidents/incident-queries';
import type { MainTabsParamList } from '../navigation/MainTabs';
import { failed, ok, renderWithQueries } from '../test-utils';
import { MAP_HINT_WITH_GPS, MAP_HINT_WITHOUT_GPS, NewIncidentScreen } from './NewIncidentScreen';

jest.mock('@urbanpulse/api-client', () => ({
  createIncident: jest.fn(),
  listCategories: jest.fn(),
}));
jest.mock('../session/SessionContext', () => ({ useSession: () => ({ api: {} }) }));
jest.mock('../hooks/useIncidentLocation', () => ({ useIncidentLocation: jest.fn() }));

type Props = BottomTabScreenProps<MainTabsParamList, 'NewReport'>;

const mockedCreateIncident = jest.mocked(createIncident);
const mockedListCategories = jest.mocked(listCategories);
const mockedLocation = jest.mocked(useIncidentLocation);

const LIGHTING = {
  id: 'fd4a9835-80be-4b73-bc9f-a042bed62b61',
  name: 'Street lighting',
  description: 'Broken, flickering or always-on street lights.',
};
const WASTE = {
  id: 'cffa0e9c-1e8e-4502-aa41-1d0d4e2306ee',
  name: 'Waste',
  description: null,
};

const LOCATED: UseIncidentLocationResult = {
  gpsFix: { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 },
  isLoading: false,
  error: null,
  permissionDenied: false,
  refreshLocation: jest.fn(async () => undefined),
  openSettings: jest.fn(async () => undefined),
};

const CREATED: CreateIncidentResult = {
  ok: true,
  data: {
    id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
    title: 'Street light off',
    description: 'Off for three days.',
    status: 'REPORTED',
    category: null,
    location: {
      latitude: 36.7213,
      longitude: -4.4214,
      accuracy: 12.5,
      address: null,
      neighborhood: null,
      district: null,
    },
    reportedAt: '2026-11-02T09:58:31Z',
    updatedAt: '2026-11-02T09:58:31Z',
    closedAt: null,
  },
};

/** A call that stays in flight until the test answers it. */
function deferred<T>() {
  let answer: (value: T) => void = () => undefined;
  const promise = new Promise<T>((resolve) => {
    answer = resolve;
  });
  return { promise, answer };
}

async function renderScreen() {
  const navigation = { navigate: jest.fn() };
  const queryClient = await renderWithQueries(
    <NewIncidentScreen
      navigation={navigation as unknown as Props['navigation']}
      route={{ key: 'NewReport', name: 'NewReport' }}
    />,
  );
  return { navigation, queryClient };
}

// The hook is mocked, so its messages are not exported here.
const PERMISSION_ERROR = 'Location is off. Move the map to put the pin on the incident.';

const NO_GPS: UseIncidentLocationResult = {
  ...LOCATED,
  gpsFix: null,
  permissionDenied: true,
  error: PERMISSION_ERROR,
};

function map() {
  return screen.getByTestId('maplibre-map');
}

/** The citizen moves the map, so the pin in its centre points somewhere else. */
async function moveMapTo(longitude: number, latitude: number) {
  await act(() =>
    map().props.onRegionDidChange({
      nativeEvent: { center: [longitude, latitude], userInteraction: true },
    }),
  );
}

async function fillIn(title: string, description: string) {
  await fireEvent.changeText(screen.getByLabelText('Title'), title);
  await fireEvent.changeText(screen.getByLabelText('Description'), description);
}

beforeEach(() => {
  mockedCreateIncident.mockReset();
  mockedListCategories.mockReset().mockResolvedValue(ok([LIGHTING, WASTE]));
  mockedLocation.mockReset().mockReturnValue(LOCATED);
  jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('NewIncidentScreen', () => {
  describe('categories', () => {
    it('shows a loading indicator, then the categories to choose from', async () => {
      await renderScreen();

      expect(screen.getByLabelText('Loading the categories')).toBeOnTheScreen();
      expect(await screen.findByRole('button', { name: 'Street lighting' })).toBeOnTheScreen();
      expect(screen.getByRole('button', { name: 'Waste' })).toBeOnTheScreen();
      expect(screen.getByRole('button', { name: 'None' })).toBeSelected();
    });

    it('explains a failure and loads them on "Try again"', async () => {
      mockedListCategories.mockResolvedValue(failed({ kind: 'network' }));
      await renderScreen();

      expect(await screen.findByText(/UrbanPulse cannot be reached/)).toBeOnTheScreen();

      mockedListCategories.mockResolvedValue(ok([LIGHTING]));
      await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByRole('button', { name: 'Street lighting' })).toBeOnTheScreen();
    });

    it('keeps the loaded categories when a refresh fails', async () => {
      const { queryClient } = await renderScreen();
      await screen.findByRole('button', { name: 'Street lighting' });
      mockedListCategories.mockResolvedValue(failed({ kind: 'network' }));

      await act(() => queryClient.refetchQueries({ queryKey: categoryKeys.active }));
      // React Query tells the screen in a timeout of its own: let it re-render first.
      await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

      expect(queryClient.getQueryState(categoryKeys.active)?.status).toBe('error');

      expect(screen.getByRole('button', { name: 'Street lighting' })).toBeOnTheScreen();
      expect(screen.queryByText(/UrbanPulse cannot be reached/)).not.toBeOnTheScreen();
    });

    it('still lets the citizen report when they cannot be loaded', async () => {
      mockedListCategories.mockResolvedValue(
        failed({ kind: 'problem', status: 500, code: undefined }),
      );
      mockedCreateIncident.mockResolvedValue(CREATED);
      await renderScreen();
      expect(await screen.findByText(/You can report without one/)).toBeOnTheScreen();

      await fillIn('Street light off', 'Off for three days.');
      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(mockedCreateIncident).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident.mock.lastCall?.[1]).not.toHaveProperty('categoryId');
    });
  });

  describe('location', () => {
    it('cannot be sent while the location is not known yet', async () => {
      mockedLocation.mockReturnValue({ ...LOCATED, gpsFix: null, isLoading: true });
      await renderScreen();
      await act(() => map().props.onDidFinishLoadingMap());
      await fillIn('Street light off', 'Off for three days.');

      expect(screen.getByText('Getting your location…')).toBeOnTheScreen();
      expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
    });

    it('lets a citizen who rejects the location report on the pin, without accuracy', async () => {
      mockedLocation.mockReturnValue(NO_GPS);
      mockedCreateIncident.mockResolvedValue(CREATED);
      await renderScreen();
      await act(() => map().props.onDidFinishLoadingMap());
      await fillIn('Street light off', 'Off for three days.');

      expect(screen.getByText(PERMISSION_ERROR)).toBeOnTheScreen();
      expect(screen.getByRole('button', { name: 'Open settings' })).toBeOnTheScreen();
      expect(screen.getByText(PLACED_BY_HAND)).toBeOnTheScreen();
      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(mockedCreateIncident).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident.mock.lastCall?.[1].location).toEqual({
        latitude: 36.72,
        longitude: -4.42,
      });
    });

    it('sends the corrected position, without the GPS accuracy, once the pin moves', async () => {
      mockedCreateIncident.mockResolvedValue(CREATED);
      await renderScreen();
      await act(() => map().props.onDidFinishLoadingMap());
      expect(screen.getByText('Your GPS position. Accuracy: 13 m.')).toBeOnTheScreen();

      await moveMapTo(-4.4102, 36.7301);
      await fillIn('Street light off', 'Off for three days.');
      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      expect(screen.getByText(PLACED_BY_HAND)).toBeOnTheScreen();
      await waitFor(() => expect(mockedCreateIncident).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident.mock.lastCall?.[1].location).toEqual({
        latitude: 36.7301,
        longitude: -4.4102,
      });
    });

    it('offers to go back to the GPS position', async () => {
      await renderScreen();

      await fireEvent.press(screen.getByRole('button', { name: 'Use my position' }));

      expect(LOCATED.refreshLocation).toHaveBeenCalled();
    });

    it('cannot be sent without the map and without a GPS fix, and offers a retry', async () => {
      mockedLocation.mockReturnValue(NO_GPS);
      await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await act(() => map().props.onDidFailLoadingMap());

      expect(screen.getByText(`${MAP_LOAD_FAILED} ${MAP_HINT_WITHOUT_GPS}`)).toBeOnTheScreen();
      expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
      expect(screen.getByLabelText('Description').props.value).toBe('Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      await act(() => map().props.onDidFinishLoadingMap());

      expect(screen.getByRole('button', { name: 'Submit' })).toBeEnabled();
    });
  });

  describe('submission', () => {
    it('asks for a title and a description before calling the API', async () => {
      await renderScreen();

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));
      expect(await screen.findByText('Enter a title for the incident.')).toBeOnTheScreen();

      await fireEvent.changeText(screen.getByLabelText('Title'), 'Street light off');
      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));
      expect(await screen.findByText('Enter a description of the incident.')).toBeOnTheScreen();

      expect(mockedCreateIncident).not.toHaveBeenCalled();
    });

    it('shows the field errors of a 422 next to their fields', async () => {
      mockedCreateIncident.mockResolvedValue({
        ok: false,
        failure: { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
        fieldErrors: [{ field: 'title', message: 'The title is too long.' }],
      });
      await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      expect(await screen.findByText('The title is too long.')).toBeOnTheScreen();
      expect(screen.getByText('Check the highlighted fields.')).toBeOnTheScreen();
      expect(screen.getByLabelText('Title').props.value).toBe('Street light off');
    });

    it('explains an invalid location, which has no field of its own', async () => {
      mockedCreateIncident.mockResolvedValue({
        ok: false,
        failure: { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
        fieldErrors: [{ field: 'location.latitude', message: 'Must be between -90 and 90.' }],
      });
      await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      expect(
        await screen.findByText('Check the location: Must be between -90 and 90.'),
      ).toBeOnTheScreen();
    });

    it('explains a failure without field errors and keeps what was written', async () => {
      mockedCreateIncident.mockResolvedValue({
        ok: false,
        failure: { kind: 'network' },
        fieldErrors: [],
      });
      await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      expect(await screen.findByText(/UrbanPulse cannot be reached/)).toBeOnTheScreen();
      expect(screen.getByLabelText('Description').props.value).toBe('Off for three days.');
      expect(Alert.alert).not.toHaveBeenCalled();
    });

    it('keeps the moved pin after a failed submission, so a retry sends the same place', async () => {
      mockedCreateIncident.mockResolvedValue({
        ok: false,
        failure: { kind: 'network' },
        fieldErrors: [],
      });
      await renderScreen();
      await act(() => map().props.onDidFinishLoadingMap());
      await moveMapTo(-4.4102, 36.7301);
      await fillIn('Street light off', 'Off for three days.');
      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));
      await screen.findByText(/UrbanPulse cannot be reached/);

      mockedCreateIncident.mockResolvedValue(CREATED);
      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(mockedCreateIncident).toHaveBeenCalledTimes(2));
      expect(mockedCreateIncident.mock.calls[1]?.[1]).toEqual(
        mockedCreateIncident.mock.calls[0]?.[1],
      );
      expect(mockedCreateIncident.mock.calls[1]?.[1].location).toEqual({
        latitude: 36.7301,
        longitude: -4.4102,
      });
    });

    it('cannot be sent twice while a submission is in flight', async () => {
      const reply = deferred<CreateIncidentResult>();
      mockedCreateIncident.mockReturnValue(reply.promise);
      // userEvent fires the handler without waiting for it, so the submission stays in flight.
      const user = userEvent.setup();
      await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await user.press(screen.getByRole('button', { name: 'Submit' }));
      const sending = await screen.findByRole('button', { name: 'Sending…' });
      await user.press(sending);

      expect(sending).toBeDisabled();
      expect(mockedCreateIncident).toHaveBeenCalledTimes(1);
      await act(async () => reply.answer(CREATED));
      await waitFor(() => expect(Alert.alert).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident).toHaveBeenCalledTimes(1);
    });

    it('sends the report, clears the form and refreshes "My reports"', async () => {
      mockedCreateIncident.mockResolvedValue(CREATED);
      const { navigation, queryClient } = await renderScreen();
      queryClient.setQueryData(incidentKeys.myReports('open'), { pages: [], pageParams: [] });
      await fillIn('  Street light off  ', ' Off for three days. ');
      await fireEvent.press(await screen.findByRole('button', { name: 'Street lighting' }));

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(Alert.alert).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident).toHaveBeenCalledWith(expect.anything(), {
        title: 'Street light off',
        description: 'Off for three days.',
        categoryId: LIGHTING.id,
        location: { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 },
      });
      expect(queryClient.getQueryState(incidentKeys.myReports('open'))?.isInvalidated).toBe(true);
      expect(screen.getByLabelText('Title').props.value).toBe('');
      expect(screen.getByLabelText('Description').props.value).toBe('');
      expect(screen.getByRole('button', { name: 'None' })).toBeSelected();

      const [, , buttons] = jest.mocked(Alert.alert).mock.lastCall ?? [];
      buttons?.[0]?.onPress?.();
      expect(navigation.navigate).toHaveBeenCalledWith('MyReports');
    });

    it('offers to add photos to the report just created (#193)', async () => {
      mockedCreateIncident.mockResolvedValue(CREATED);
      const { navigation } = await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(Alert.alert).toHaveBeenCalledTimes(1));
      const [, , buttons] = jest.mocked(Alert.alert).mock.lastCall ?? [];
      buttons?.find((button) => button.text === 'Add photos')?.onPress?.();
      expect(navigation.navigate).toHaveBeenCalledWith('MyReports', {
        screen: 'AddPhotos',
        params: { incidentId: CREATED.ok ? CREATED.data.id : '' },
        initial: false,
      });
    });

    it('leaves out the accuracy when the device does not report it', async () => {
      mockedLocation.mockReturnValue({
        ...LOCATED,
        gpsFix: { latitude: 36.7213, longitude: -4.4214, accuracy: null },
      });
      mockedCreateIncident.mockResolvedValue(CREATED);
      await renderScreen();
      await fillIn('Street light off', 'Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(mockedCreateIncident).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident.mock.lastCall?.[1].location).toEqual({
        latitude: 36.7213,
        longitude: -4.4214,
      });
    });
  });

  describe('base map', () => {
    it('still sends the report with the GPS position when the map cannot load', async () => {
      mockedCreateIncident.mockResolvedValue(CREATED);
      await renderScreen();
      await act(() => screen.getByTestId('maplibre-map').props.onDidFailLoadingMap());
      expect(screen.getByText(`${MAP_LOAD_FAILED} ${MAP_HINT_WITH_GPS}`)).toBeOnTheScreen();
      await fillIn('Street light off', 'Off for three days.');

      await fireEvent.press(screen.getByRole('button', { name: 'Submit' }));

      await waitFor(() => expect(mockedCreateIncident).toHaveBeenCalledTimes(1));
      expect(mockedCreateIncident.mock.lastCall?.[1].location).toEqual({
        latitude: 36.7213,
        longitude: -4.4214,
        accuracy: 12.5,
      });
    });
  });
});
