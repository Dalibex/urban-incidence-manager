import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Linking } from 'react-native';
import * as Location from 'expo-location';

import type { GpsFix } from '../location/report-location';

export interface UseIncidentLocationResult {
  /**
   * The last GPS fix; a new object for every successful read, even at the same place, and `null`
   * once a read fails or the permission is denied.
   */
  gpsFix: GpsFix | null;
  isLoading: boolean;
  error: string | null;
  permissionDenied: boolean;
  refreshLocation: () => Promise<void>;
  openSettings: () => Promise<void>;
}

/** What one read of the device location produced, without touching any React state. */
type LocationOutcome =
  { kind: 'denied' } | { kind: 'position'; fix: GpsFix } | { kind: 'failure'; message: string };

/** Long enough for a first fix outdoors; past it the citizen places the pin by hand. */
export const GPS_TIMEOUT_MS = 15_000;

export const PERMISSION_ERROR = 'Location is off. Move the map to put the pin on the incident.';
export const GPS_TIMEOUT_ERROR =
  'Your position could not be found in time. Move the map to put the pin on the incident.';
export const GPS_FAILED_ERROR =
  'Your position could not be read. Move the map to put the pin on the incident.';

class GpsTimeoutError extends Error {}

/** `getCurrentPositionAsync` has no timeout of its own and can wait for a fix forever. */
async function currentPositionWithin(timeoutMs: number): Promise<Location.LocationObject> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new GpsTimeoutError()), timeoutMs);
  });
  try {
    return await Promise.race([
      Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
      timeout,
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** Requests the foreground permission and returns the read outcome; it never sets React state. */
async function readLocation(): Promise<LocationOutcome> {
  try {
    const { status } = await Location.requestForegroundPermissionsAsync();
    if (status !== Location.PermissionStatus.GRANTED) {
      return { kind: 'denied' };
    }
    const position = await currentPositionWithin(GPS_TIMEOUT_MS);
    return {
      kind: 'position',
      fix: {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracy: position.coords.accuracy ?? null,
      },
    };
  } catch (error) {
    if (!(error instanceof GpsTimeoutError)) {
      // The citizen sees a plain message; the cause stays in the log.
      console.warn('Could not read the location', error);
    }
    return {
      kind: 'failure',
      message: error instanceof GpsTimeoutError ? GPS_TIMEOUT_ERROR : GPS_FAILED_ERROR,
    };
  }
}

/**
 * Requests foreground location permission and reads the current WGS 84 position. A denied
 * permission, a failure or a timeout are states, not dead ends (RF26, AGENTS.md section 7): the
 * citizen places the pin by hand. When the citizen comes back to the app after allowing the
 * location in the settings, it is read again.
 */
export function useIncidentLocation(): UseIncidentLocationResult {
  const [gpsFix, setGpsFix] = useState<GpsFix | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [permissionDenied, setPermissionDenied] = useState<boolean>(false);
  const permissionDeniedRef = useRef(false);

  /** Applies an outcome to the state; it must be used inside a callback, not in an effect body. */
  const applyOutcome = useCallback((outcome: LocationOutcome) => {
    permissionDeniedRef.current = outcome.kind === 'denied';
    if (outcome.kind !== 'position') {
      // A read that failed says the last fix may no longer be where the phone is: keeping it would
      // show it, and send it with its accuracy, next to the error.
      setGpsFix(null);
    }
    if (outcome.kind === 'denied') {
      setPermissionDenied(true);
      setError(PERMISSION_ERROR);
      return;
    }
    setPermissionDenied(false);
    if (outcome.kind === 'failure') {
      setError(outcome.message);
      return;
    }
    setGpsFix(outcome.fix);
    setError(null);
  }, []);

  useEffect(() => {
    // The mount read: state changes live in the promise callbacks, not in the effect body, and
    // `isLoading` already starts as `true`.
    void readLocation()
      .then(applyOutcome)
      .finally(() => setIsLoading(false));
  }, [applyOutcome]);

  const refreshLocation = useCallback(async () => {
    // Immediate feedback while the permission and the GPS fix are requested.
    setIsLoading(true);
    setError(null);
    applyOutcome(await readLocation());
    setIsLoading(false);
  }, [applyOutcome]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active' || !permissionDeniedRef.current) {
        return;
      }
      // Only checks: asking again here would show the system dialog every time the app opens.
      Location.getForegroundPermissionsAsync()
        .then(({ status }) => {
          if (status === Location.PermissionStatus.GRANTED) {
            void refreshLocation();
          }
        })
        // The pin still works; the citizen can also tap "Use my position".
        .catch((cause: unknown) => console.warn('Could not check the location permission', cause));
    });
    return () => subscription.remove();
  }, [refreshLocation]);

  const openSettings = useCallback(async () => {
    await Linking.openSettings();
  }, []);

  return {
    gpsFix,
    isLoading,
    error,
    permissionDenied,
    refreshLocation,
    openSettings,
  };
}
