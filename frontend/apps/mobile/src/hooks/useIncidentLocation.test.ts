import { act, renderHook, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import { AppState, type AppStateStatus } from 'react-native';

import {
  GPS_FAILED_ERROR,
  GPS_TIMEOUT_ERROR,
  GPS_TIMEOUT_MS,
  PERMISSION_ERROR,
  useIncidentLocation,
} from './useIncidentLocation';

jest.mock('expo-location', () => ({
  PermissionStatus: { GRANTED: 'granted', DENIED: 'denied' },
  Accuracy: { Balanced: 3 },
  requestForegroundPermissionsAsync: jest.fn(),
  getForegroundPermissionsAsync: jest.fn(),
  getCurrentPositionAsync: jest.fn(),
}));

const requestPermission = jest.mocked(Location.requestForegroundPermissionsAsync);
const checkPermission = jest.mocked(Location.getForegroundPermissionsAsync);
const currentPosition = jest.mocked(Location.getCurrentPositionAsync);

const GRANTED = { status: 'granted' } as Location.LocationPermissionResponse;
const DENIED = { status: 'denied' } as Location.LocationPermissionResponse;

function position(accuracy: number | null): Location.LocationObject {
  return {
    timestamp: 0,
    coords: {
      latitude: 36.7213,
      longitude: -4.4214,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
    },
  };
}

let appStateListener: ((state: AppStateStatus) => void) | undefined;

beforeEach(() => {
  requestPermission.mockReset().mockResolvedValue(GRANTED);
  checkPermission.mockReset().mockResolvedValue(GRANTED);
  currentPosition.mockReset().mockResolvedValue(position(12.5));
  appStateListener = undefined;
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_, listener) => {
    appStateListener = listener;
    return { remove: jest.fn() };
  });
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

describe('useIncidentLocation', () => {
  it('reads the GPS fix with its accuracy', async () => {
    const { result } = await renderHook(() => useIncidentLocation());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.gpsFix).toEqual({
      latitude: 36.7213,
      longitude: -4.4214,
      accuracy: 12.5,
    });
    expect(result.current.error).toBeNull();
  });

  it('leaves the accuracy unknown, not zero, when the device does not report it', async () => {
    currentPosition.mockResolvedValue(position(null));

    const { result } = await renderHook(() => useIncidentLocation());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.gpsFix?.accuracy).toBeNull();
  });

  it('explains that the pin can be placed by hand when the permission is denied', async () => {
    requestPermission.mockResolvedValue(DENIED);

    const { result } = await renderHook(() => useIncidentLocation());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.permissionDenied).toBe(true);
    expect(result.current.gpsFix).toBeNull();
    expect(result.current.error).toBe(PERMISSION_ERROR);
  });

  it('stops waiting for a GPS fix that does not arrive', async () => {
    jest.useFakeTimers();
    currentPosition.mockReturnValue(new Promise(() => undefined));

    const { result } = await renderHook(() => useIncidentLocation());
    expect(result.current.isLoading).toBe(true);

    await act(async () => jest.advanceTimersByTimeAsync(GPS_TIMEOUT_MS));

    expect(result.current.isLoading).toBe(false);
    expect(result.current.gpsFix).toBeNull();
    expect(result.current.error).toBe(GPS_TIMEOUT_ERROR);
  });

  it('explains a GPS failure without the technical cause', async () => {
    currentPosition.mockRejectedValue(new Error('Location services are disabled'));

    const { result } = await renderHook(() => useIncidentLocation());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBe(GPS_FAILED_ERROR);
    expect(result.current.permissionDenied).toBe(false);
  });

  it('gives a new fix on every refresh, even at the same place', async () => {
    const { result } = await renderHook(() => useIncidentLocation());
    await waitFor(() => expect(result.current.gpsFix).not.toBeNull());
    const first = result.current.gpsFix;

    await act(() => result.current.refreshLocation());

    expect(result.current.gpsFix).toEqual(first);
    expect(result.current.gpsFix).not.toBe(first);
  });

  it('reads the position on returning from the settings with the location allowed', async () => {
    requestPermission.mockResolvedValue(DENIED);
    const { result } = await renderHook(() => useIncidentLocation());
    await waitFor(() => expect(result.current.permissionDenied).toBe(true));

    requestPermission.mockResolvedValue(GRANTED);
    await act(async () => appStateListener?.('active'));

    await waitFor(() => expect(result.current.gpsFix).not.toBeNull());
    expect(result.current.permissionDenied).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('does not ask again on returning when the location is still off', async () => {
    requestPermission.mockResolvedValue(DENIED);
    checkPermission.mockResolvedValue(DENIED);
    const { result } = await renderHook(() => useIncidentLocation());
    await waitFor(() => expect(result.current.permissionDenied).toBe(true));

    await act(async () => appStateListener?.('active'));

    expect(checkPermission).toHaveBeenCalledTimes(1);
    expect(requestPermission).toHaveBeenCalledTimes(1);
    expect(result.current.permissionDenied).toBe(true);
  });

  it('does not read again on returning when the location was already allowed', async () => {
    const { result } = await renderHook(() => useIncidentLocation());
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    await act(async () => appStateListener?.('active'));

    expect(checkPermission).not.toHaveBeenCalled();
    expect(currentPosition).toHaveBeenCalledTimes(1);
  });
});
