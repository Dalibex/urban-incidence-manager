import type { BottomTabScreenProps } from '@react-navigation/bottom-tabs';
import { act, fireEvent, screen, userEvent, waitFor } from '@testing-library/react-native';
import { createApiClient, type SessionTokens } from '@urbanpulse/api-client';
import * as Location from 'expo-location';
import { Alert, AppState, type AppStateStatus } from 'react-native';

import type { MainTabsParamList } from '../navigation/MainTabs';
import { renderWithQueries } from '../test-utils';
import { NewIncidentScreen } from './NewIncidentScreen';

// Acceptance tests of #181 for the position pin of the report form (#198): T1 to T4, T6 to T11, T13
// and T14. The screen is the real one, with its location hook, map, indicator and API client; only
// the phone's GPS (`expo-location`), the network (`fetch`) and the native map are replaced. The map
// mock keeps its props, so a test moves the map by firing the event MapLibre sends when it stops.
// T5 is the `frontend` CI job; T12 and T15 are manual cases, and T1, T2, T3, T6, T8, T9 and T13 are
// also checked by hand on a phone.

jest.mock('expo-location', () => ({
  PermissionStatus: { GRANTED: 'granted', DENIED: 'denied' },
  Accuracy: { Balanced: 3 },
  requestForegroundPermissionsAsync: jest.fn(),
  getForegroundPermissionsAsync: jest.fn(),
  getCurrentPositionAsync: jest.fn(),
}));

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'access',
  accessTokenExpiresAt: '2026-11-12T10:15:00Z',
  refreshToken: 'refresh',
  refreshTokenExpiresAt: '2026-11-26T10:00:00Z',
};

const mockSend = jest.fn<Promise<Response>, [Request]>();
const mockApi = createApiClient('http://api.test/api/v1', {
  tokenStore: {
    load: async () => TOKENS,
    save: async () => undefined,
    clear: async () => undefined,
  },
  fetch: (request) => mockSend(request),
});
jest.mock('../session/SessionContext', () => ({ useSession: () => ({ api: mockApi }) }));

const requestPermission = jest.mocked(Location.requestForegroundPermissionsAsync);
const checkPermission = jest.mocked(Location.getForegroundPermissionsAsync);
const currentPosition = jest.mocked(Location.getCurrentPositionAsync);

const { cameraMethods } = jest.requireMock<{
  cameraMethods: { easeTo: jest.Mock; jumpTo: jest.Mock };
}>('@maplibre/maplibre-react-native');

type Props = BottomTabScreenProps<MainTabsParamList, 'NewReport'>;

const GRANTED = { status: 'granted' } as Location.LocationPermissionResponse;
const DENIED = { status: 'denied' } as Location.LocationPermissionResponse;

/** Calle Larios, as the phone's GPS reads it. */
const GPS = { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 };
/** The Atarazanas market, where the citizen moves the pin. */
const MOVED = { latitude: 36.7187, longitude: -4.4246 };
/** Where the map starts without a GPS fix. */
const MALAGA = { latitude: 36.72, longitude: -4.42 };

const GPS_TIMEOUT_MS = 15_000;
const NETWORK_MESSAGE = /UrbanPulse cannot be reached/;

function position(): Location.LocationObject {
  return {
    timestamp: 0,
    coords: {
      latitude: GPS.latitude,
      longitude: GPS.longitude,
      accuracy: GPS.accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
    },
  };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function created(location: object): Response {
  return json(201, {
    id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
    title: 'Loose paving stones',
    description: 'Three stones move when you step on them.',
    status: 'REPORTED',
    category: null,
    location: { accuracy: null, address: null, neighborhood: null, district: null, ...location },
    reportedAt: '2026-11-12T09:58:31Z',
    updatedAt: '2026-11-12T09:58:31Z',
    closedAt: null,
  });
}

/** The bodies of the reports the app sent to `POST /incidents`, in order. */
const sentReports: unknown[] = [];

/** How the API answers a report; the categories always load. */
let answerReport: (body: { location: object }) => Response | Promise<Response>;

let appStateListener: ((state: AppStateStatus) => void) | undefined;

beforeEach(() => {
  sentReports.length = 0;
  answerReport = (body) => created(body.location);
  mockSend.mockReset().mockImplementation(async (request) => {
    if (request.method === 'GET' && request.url.endsWith('/categories')) {
      return json(200, []);
    }
    if (request.method === 'POST' && request.url.endsWith('/incidents')) {
      const body = (await request.clone().json()) as { location: object };
      sentReports.push(body);
      return answerReport(body);
    }
    throw new Error(`Unexpected request: ${request.method} ${request.url}`);
  });
  requestPermission.mockReset().mockResolvedValue(GRANTED);
  checkPermission.mockReset().mockResolvedValue(GRANTED);
  currentPosition.mockReset().mockResolvedValue(position());
  cameraMethods.easeTo.mockReset();
  cameraMethods.jumpTo.mockReset();
  appStateListener = undefined;
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_, listener) => {
    appStateListener = listener;
    return { remove: jest.fn() };
  });
  jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

async function openForm() {
  const navigation = { navigate: jest.fn() };
  await renderWithQueries(
    <NewIncidentScreen
      navigation={navigation as unknown as Props['navigation']}
      route={{ key: 'NewReport', name: 'NewReport' }}
    />,
  );
}

function map() {
  return screen.getByTestId('maplibre-map');
}

async function mapLoads() {
  await act(() => map().props.onDidFinishLoadingMap());
}

/** The citizen drags the map until the pin in its centre is on `place`. */
async function moveMapTo(place: { latitude: number; longitude: number }) {
  await act(() =>
    map().props.onRegionDidChange({
      nativeEvent: { center: [place.longitude, place.latitude], userInteraction: true },
    }),
  );
}

async function fillIn() {
  await fireEvent.changeText(screen.getByLabelText('Title'), 'Loose paving stones');
  await fireEvent.changeText(
    screen.getByLabelText('Description'),
    'Three stones move when you step on them.',
  );
}

function submitButton() {
  return screen.getByRole('button', { name: 'Submit' });
}

async function submit() {
  await fireEvent.press(submitButton());
}

describe('#181 position pin of the report form', () => {
  it('T1: with the location allowed, the map opens on the GPS position and the pin can be moved', async () => {
    await openForm();
    await mapLoads();

    expect(await screen.findByText('Your GPS position. Accuracy: 13 m.')).toBeOnTheScreen();
    expect(cameraMethods.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({ center: [GPS.longitude, GPS.latitude] }),
    );
    expect(screen.getByTestId('map-pin')).toBeOnTheScreen();

    await moveMapTo(MOVED);

    expect(screen.getByText('Position chosen on the map.')).toBeOnTheScreen();
  });

  it('T2 and T4: the report sends the corrected position, latitude and longitude in place', async () => {
    await openForm();
    await mapLoads();
    await screen.findByText(/Your GPS position/);

    await moveMapTo(MOVED);
    await fillIn();
    await submit();

    await waitFor(() => expect(sentReports).toHaveLength(1));
    expect(sentReports[0]).toMatchObject({ location: MOVED });
    await waitFor(() => expect(Alert.alert).toHaveBeenCalledTimes(1));
  });

  it('T3: with the location rejected, the map opens on Málaga and the citizen reports once the pin is placed', async () => {
    requestPermission.mockResolvedValue(DENIED);
    await openForm();
    await mapLoads();

    expect(
      await screen.findByText('Location is off. Move the map to put the pin on the incident.'),
    ).toBeOnTheScreen();
    expect(screen.getByTestId('maplibre-camera')).toHaveProp(
      'initialViewState',
      expect.objectContaining({ center: [MALAGA.longitude, MALAGA.latitude] }),
    );
    expect(screen.getByRole('button', { name: 'Open settings' })).toBeOnTheScreen();
    await fillIn();
    // Málaga is where the map opens, not where the incident is: it cannot be sent as it is.
    expect(screen.queryByText('Position chosen on the map.')).not.toBeOnTheScreen();
    expect(submitButton()).toBeDisabled();

    await moveMapTo(MOVED);
    await submit();

    await waitFor(() => expect(sentReports).toHaveLength(1));
    expect(sentReports[0]).toMatchObject({ location: MOVED });
    expect(currentPosition).not.toHaveBeenCalled();
  });

  it('T6: when the GPS takes too long, the form stops loading, says why and lets the citizen place the pin', async () => {
    jest.useFakeTimers();
    currentPosition.mockReturnValue(new Promise(() => undefined));
    await openForm();
    await mapLoads();
    expect(screen.getByText('Getting your location…')).toBeOnTheScreen();
    expect(submitButton()).toBeDisabled();

    await act(async () => jest.advanceTimersByTimeAsync(GPS_TIMEOUT_MS));

    expect(screen.queryByText('Getting your location…')).not.toBeOnTheScreen();
    expect(
      screen.getByText(
        'Your position could not be found in time. Move the map to put the pin on the incident.',
      ),
    ).toBeOnTheScreen();
    expect(screen.queryByText('Position chosen on the map.')).not.toBeOnTheScreen();
    expect(submitButton()).toBeDisabled();

    await moveMapTo(MOVED);

    expect(screen.getByText('Position chosen on the map.')).toBeOnTheScreen();
    expect(submitButton()).toBeEnabled();
  });

  it('T6: when the GPS fails, the form says so in plain words and lets the citizen report', async () => {
    currentPosition.mockRejectedValue(new Error('Location services are disabled'));
    await openForm();
    await mapLoads();

    expect(
      await screen.findByText(
        'Your position could not be read. Move the map to put the pin on the incident.',
      ),
    ).toBeOnTheScreen();
    expect(screen.queryByText(/Location services are disabled/)).not.toBeOnTheScreen();
    await fillIn();
    expect(submitButton()).toBeDisabled();

    await moveMapTo(MOVED);
    await submit();

    await waitFor(() => expect(sentReports).toHaveLength(1));
    expect(sentReports[0]).toMatchObject({ location: MOVED });
  });

  describe('T7: accuracy', () => {
    it('sends the GPS accuracy while the pin is on the GPS position', async () => {
      await openForm();
      await mapLoads();
      await screen.findByText(/Your GPS position/);

      await fillIn();
      await submit();

      await waitFor(() => expect(sentReports).toHaveLength(1));
      expect(sentReports[0]).toEqual(expect.objectContaining({ location: GPS }));
    });

    it('sends no accuracy once the pin was moved', async () => {
      await openForm();
      await mapLoads();
      await screen.findByText(/Your GPS position/);

      await moveMapTo(MOVED);
      await fillIn();
      await submit();

      await waitFor(() => expect(sentReports).toHaveLength(1));
      expect(sentReports[0]).toEqual(expect.objectContaining({ location: MOVED }));
    });

    it('sends no accuracy, never 0, when there was no GPS', async () => {
      requestPermission.mockResolvedValue(DENIED);
      await openForm();
      await mapLoads();
      await screen.findByText(/Location is off/);

      await moveMapTo(MOVED);
      await fillIn();
      await submit();

      await waitFor(() => expect(sentReports).toHaveLength(1));
      expect(sentReports[0]).toEqual(expect.objectContaining({ location: MOVED }));
    });

    it('goes back to the GPS position and its accuracy with "Use my position"', async () => {
      await openForm();
      await mapLoads();
      await screen.findByText(/Your GPS position/);
      await moveMapTo(MOVED);

      await fireEvent.press(screen.getByRole('button', { name: 'Use my position' }));

      expect(await screen.findByText('Your GPS position. Accuracy: 13 m.')).toBeOnTheScreen();
      await fillIn();
      await submit();
      await waitFor(() => expect(sentReports).toHaveLength(1));
      expect(sentReports[0]).toEqual(expect.objectContaining({ location: GPS }));
    });
  });

  describe('T8: tiles not reachable', () => {
    it('with GPS, explains it and the citizen can still report on the GPS position', async () => {
      await openForm();
      await screen.findByText(/Your GPS position/);

      await act(() => map().props.onDidFailLoadingMap());

      expect(
        screen.getByText('The map could not load. The incident will use your GPS position.'),
      ).toBeOnTheScreen();
      await fillIn();
      await submit();
      await waitFor(() => expect(sentReports).toHaveLength(1));
      expect(sentReports[0]).toEqual(expect.objectContaining({ location: GPS }));
    });

    it('without GPS, keeps the text, cannot be sent and offers a retry', async () => {
      requestPermission.mockResolvedValue(DENIED);
      await openForm();
      await screen.findByText(/Location is off/);
      await fillIn();

      await act(() => map().props.onDidFailLoadingMap());

      expect(screen.getByText(/The map could not load\. Without the map/)).toBeOnTheScreen();
      expect(submitButton()).toBeDisabled();
      expect(screen.getByLabelText('Title').props.value).toBe('Loose paving stones');

      await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      await mapLoads();
      await moveMapTo(MOVED);

      expect(submitButton()).toBeEnabled();
      expect(screen.getByLabelText('Description').props.value).toBe(
        'Three stones move when you step on them.',
      );
    });
  });

  it('T9: the map cannot leave the area the base map covers', async () => {
    await openForm();

    expect(screen.getByTestId('maplibre-camera')).toHaveProp(
      'maxBounds',
      [-5.62, 36.28, -3.75, 37.3],
    );
  });

  it('T10: without network on Send, it offers a retry and keeps the title, description and pin', async () => {
    answerReport = () => {
      throw new TypeError('Network request failed');
    };
    await openForm();
    await mapLoads();
    await screen.findByText(/Your GPS position/);
    await moveMapTo(MOVED);
    await fillIn();

    await submit();

    expect(await screen.findByText(NETWORK_MESSAGE)).toBeOnTheScreen();
    expect(screen.getByLabelText('Title').props.value).toBe('Loose paving stones');
    expect(screen.getByLabelText('Description').props.value).toBe(
      'Three stones move when you step on them.',
    );
    expect(screen.getByText('Position chosen on the map.')).toBeOnTheScreen();

    answerReport = (body) => created(body.location);
    await submit();

    await waitFor(() => expect(Alert.alert).toHaveBeenCalledTimes(1));
    expect(sentReports).toHaveLength(2);
    expect(sentReports[1]).toEqual(sentReports[0]);
    expect(sentReports[1]).toMatchObject({ location: MOVED });
  });

  it('T11: a double tap on Send creates one incident', async () => {
    let answer: (response: Response) => void = () => undefined;
    answerReport = () =>
      new Promise<Response>((resolve) => {
        answer = resolve;
      });
    // userEvent fires the handler without waiting for it, so the first submission stays in flight.
    const user = userEvent.setup();
    await openForm();
    await mapLoads();
    await screen.findByText(/Your GPS position/);
    await fillIn();

    await user.press(submitButton());
    await user.press(await screen.findByRole('button', { name: 'Sending…' }));

    await act(async () => answer(created(GPS)));
    await waitFor(() => expect(Alert.alert).toHaveBeenCalledTimes(1));
    expect(sentReports).toHaveLength(1);
  });

  it('T13: after rejecting, allowing the location in the settings lets the GPS position be used', async () => {
    requestPermission.mockResolvedValue(DENIED);
    checkPermission.mockResolvedValue(DENIED);
    await openForm();
    await mapLoads();
    await screen.findByText(/Location is off/);

    requestPermission.mockResolvedValue(GRANTED);
    checkPermission.mockResolvedValue(GRANTED);
    await act(async () => appStateListener?.('active'));

    expect(await screen.findByText('Your GPS position. Accuracy: 13 m.')).toBeOnTheScreen();
    expect(cameraMethods.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({ center: [GPS.longitude, GPS.latitude] }),
    );
    expect(screen.queryByRole('button', { name: 'Open settings' })).not.toBeOnTheScreen();
  });

  it('T14: the attribution of the tiles is on screen', async () => {
    await openForm();

    expect(screen.getByText('© OpenMapTiles © OpenStreetMap contributors')).toBeOnTheScreen();
  });
});
