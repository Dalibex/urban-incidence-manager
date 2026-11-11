import {
  Camera,
  Map,
  type CameraRef,
  type ViewStateChangeEvent,
} from '@maplibre/maplibre-react-native';
import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { StyleSheet, Text, View, type NativeSyntheticEvent } from 'react-native';

import {
  COVERED_AREA,
  fromLngLat,
  MALAGA_CENTER,
  toLngLat,
  type GpsFix,
  type MapPin,
} from '../location/report-location';
import { Button } from './Button';

/**
 * Required by the ODbL licence of the tiles (ADR-0016). It stays on screen as text: the native
 * attribution button hides it behind a tap.
 */
export const MAP_ATTRIBUTION = '© OpenMapTiles © OpenStreetMap contributors';

export const MAP_NOT_CONFIGURED = 'The map is not configured.';
export const MAP_LOAD_FAILED = 'The map could not load.';
export const MOVE_THE_MAP = 'Move the map to put the pin on the incident';

const CITY_ZOOM = 12;
const STREET_ZOOM = 16;
const RECENTER_DURATION_MS = 500;
const MAP_HEIGHT = 260;
const PIN_HEAD_SIZE = 22;
const PIN_STEM_HEIGHT = 14;

export interface BaseMapProps {
  /** The last GPS fix. Every new fix centres the map on it. */
  gpsFix: GpsFix | null;
  /** Where the pin points; `null` while the map is not on screen. */
  onPinChange: (pin: MapPin | null) => void;
  /** What the form can still do when the map is not on screen. */
  unavailableHint: string;
}

/**
 * Self-hosted OpenStreetMap base map (ADR-0016) with a pin fixed in its centre: the citizen moves
 * the map under the pin. The style comes from `EXPO_PUBLIC_MAP_STYLE_URL` and loads the tiles
 * through `pmtiles://`. A map that is missing or fails to load never blocks the form around it: it
 * shows a notice and offers to try again.
 */
export function BaseMap({ gpsFix, onPinChange, unavailableHint }: BaseMapProps) {
  // Expo inlines EXPO_PUBLIC_* variables at build time, only when written out in full like this.
  const styleUrl = process.env.EXPO_PUBLIC_MAP_STYLE_URL;
  const [failed, setFailed] = useState(false);
  // A new key mounts a new native map, which loads the style again.
  const [attempt, setAttempt] = useState(0);
  const cameraRef = useRef<CameraRef>(null);
  const loadedRef = useRef(false);
  const pinOnGpsFixRef = useRef(false);
  const latestFixRef = useRef(gpsFix);

  useEffect(() => {
    if (gpsFix === null || gpsFix === latestFixRef.current) {
      return;
    }
    latestFixRef.current = gpsFix;
    cameraRef.current?.easeTo({
      center: toLngLat(gpsFix),
      zoom: STREET_ZOOM,
      duration: RECENTER_DURATION_MS,
    });
    if (loadedRef.current) {
      pinOnGpsFixRef.current = true;
      onPinChange({ latitude: gpsFix.latitude, longitude: gpsFix.longitude, onGpsFix: true });
    }
  }, [gpsFix, onPinChange]);

  function handleLoaded() {
    loadedRef.current = true;
    const fix = latestFixRef.current;
    if (fix === null) {
      pinOnGpsFixRef.current = false;
      onPinChange({ ...MALAGA_CENTER, onGpsFix: false });
      return;
    }
    // A fix that arrived while the style was loading may have missed the camera.
    cameraRef.current?.jumpTo({ center: toLngLat(fix), zoom: STREET_ZOOM });
    pinOnGpsFixRef.current = true;
    onPinChange({ latitude: fix.latitude, longitude: fix.longitude, onGpsFix: true });
  }

  function handleRegionChanged({ nativeEvent }: NativeSyntheticEvent<ViewStateChangeEvent>) {
    if (!loadedRef.current) {
      return;
    }
    // The map also moves on its own when it is centred on a new fix; that keeps the fix.
    if (nativeEvent.userInteraction) {
      pinOnGpsFixRef.current = false;
    }
    onPinChange({ ...fromLngLat(nativeEvent.center), onGpsFix: pinOnGpsFixRef.current });
  }

  function handleFailed() {
    loadedRef.current = false;
    setFailed(true);
    onPinChange(null);
  }

  if (!styleUrl) {
    return <Notice message={`${MAP_NOT_CONFIGURED} ${unavailableHint}`} />;
  }

  if (failed) {
    return (
      <Notice message={`${MAP_LOAD_FAILED} ${unavailableHint}`}>
        <Button
          label="Try again"
          variant="secondary"
          onPress={() => {
            setFailed(false);
            setAttempt((current) => current + 1);
          }}
        />
      </Notice>
    );
  }

  // Read only when the native map mounts; later fixes move it through the camera.
  const start = gpsFix;
  return (
    <View style={styles.container}>
      <Map
        key={attempt}
        style={styles.map}
        mapStyle={styleUrl}
        attribution={false}
        logo={false}
        onDidFinishLoadingMap={handleLoaded}
        onDidFailLoadingMap={handleFailed}
        onRegionDidChange={handleRegionChanged}
      >
        <Camera
          ref={cameraRef}
          initialViewState={{
            center: toLngLat(start ?? MALAGA_CENTER),
            zoom: start !== null ? STREET_ZOOM : CITY_ZOOM,
          }}
          maxBounds={COVERED_AREA}
        />
      </Map>
      <View style={styles.pin} pointerEvents="none" testID="map-pin">
        <View style={styles.pinHead} />
        <View style={styles.pinStem} />
      </View>
      <Text style={styles.hint} pointerEvents="none">
        {MOVE_THE_MAP}
      </Text>
      <Text style={styles.attribution}>{MAP_ATTRIBUTION}</Text>
    </View>
  );
}

function Notice({ message, children }: { message: string; children?: ReactNode }) {
  return (
    <View style={[styles.container, styles.notice]}>
      <Text accessibilityLiveRegion="polite" style={styles.noticeText}>
        {message}
      </Text>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    height: MAP_HEIGHT,
    borderRadius: 8,
    overflow: 'hidden',
    backgroundColor: colors.surface,
  },
  map: {
    flex: 1,
  },
  // The tip of the stem sits on the centre of the map, which is the position sent.
  pin: {
    position: 'absolute',
    left: '50%',
    top: '50%',
    alignItems: 'center',
    marginLeft: -PIN_HEAD_SIZE / 2,
    marginTop: -(PIN_HEAD_SIZE + PIN_STEM_HEIGHT),
    width: PIN_HEAD_SIZE,
  },
  pinHead: {
    width: PIN_HEAD_SIZE,
    height: PIN_HEAD_SIZE,
    borderRadius: PIN_HEAD_SIZE / 2,
    borderWidth: 3,
    borderColor: colors.background,
    backgroundColor: colors.danger,
  },
  pinStem: {
    width: 3,
    height: PIN_STEM_HEIGHT,
    backgroundColor: colors.danger,
  },
  hint: {
    position: 'absolute',
    top: spacing.xs,
    alignSelf: 'center',
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
    color: colors.text,
    fontSize: 13,
  },
  attribution: {
    position: 'absolute',
    right: 0,
    bottom: 0,
    paddingHorizontal: spacing.xs,
    backgroundColor: 'rgba(255, 255, 255, 0.8)',
    color: colors.text,
    fontSize: 11,
  },
  notice: {
    justifyContent: 'center',
    gap: spacing.sm,
    padding: spacing.md,
  },
  noticeText: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
});
