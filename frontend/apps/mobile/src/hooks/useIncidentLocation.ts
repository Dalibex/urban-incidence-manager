import { useCallback, useEffect, useState } from 'react';
import { Linking } from 'react-native';
import * as Location from 'expo-location';

export interface IncidentLocation {
  latitude: number;
  longitude: number;
  accuracy: number | null;
}

export interface UseIncidentLocationResult {
  latitude: number | null;
  longitude: number | null;
  accuracy: number | null;
  isLoading: boolean;
  error: string | null;
  permissionDenied: boolean;
  refreshLocation: () => Promise<void>;
  openSettings: () => Promise<void>;
}

/** What one read of the device location produced, without touching any React state. */
type LocationOutcome =
  | { kind: 'denied' }
  | { kind: 'position'; location: IncidentLocation }
  | { kind: 'failure'; message: string };

const PERMISSION_ERROR = 'Location permission is required to report an incident.';

/**Requests the foreground permission and returns the read outcome; it never sets React state. */
async function readLocation(): Promise<LocationOutcome> {
  try {
    const { status } = await Location.requestForegroundPermissionsAsync();
    if (status !== Location.PermissionStatus.GRANTED) {
      return { kind: 'denied' };
    }
    const position = await Location.getCurrentPositionAsync({
      accuracy: Location.Accuracy.Balanced,
    });
    return {
      kind: 'position',
      location: {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracy: position.coords.accuracy ?? null,
      },
    };
  } catch (error) {
    return {
      kind: 'failure',
      message: error instanceof Error ? error.message : 'Could not read the location.',
    };
  }
}

/**
 * Requests foreground location permission and reads the current WGS 84 position.
 * Permission denial is a first-class state (graceful degradation, RF26): the caller can
 * offer a button that opens the OS settings.
 */
export function useIncidentLocation(): UseIncidentLocationResult {
  const [latitude, setLatitude] = useState<number | null>(null);
  const [longitude, setLongitude] = useState<number | null>(null);
  const [accuracy, setAccuracy] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [permissionDenied, setPermissionDenied] = useState<boolean>(false);

  /** Applies an outcome to the state; it must be used inside a callback, not in an effect body. */
  const applyOutcome = useCallback((outcome: LocationOutcome) => {
    if (outcome.kind === 'denied') {
      setPermissionDenied(true);
      setError(PERMISSION_ERROR);
      return;
    }
    if (outcome.kind === 'failure') {
      setError(outcome.message);
      return;
    }
    setPermissionDenied(false);
    setLatitude(outcome.location.latitude);
    setLongitude(outcome.location.longitude);
    setAccuracy(outcome.location.accuracy);
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

  const openSettings = useCallback(async () => {
    await Linking.openSettings();
  }, []);

  return {
    latitude,
    longitude,
    accuracy,
    isLoading,
    error,
    permissionDenied,
    refreshLocation,
    openSettings,
  };
}
