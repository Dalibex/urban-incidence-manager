import { Camera, Map } from '@maplibre/maplibre-react-native';
import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { useState, type ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { Button } from './Button';

/**
 * Required by the ODbL licence of the tiles (ADR-0016). It stays on screen as text: the native
 * attribution button hides it behind a tap.
 */
export const MAP_ATTRIBUTION = '© OpenMapTiles © OpenStreetMap contributors';

/** Centre of Málaga as [longitude, latitude] (WGS 84), the order MapLibre expects. */
const MALAGA_CENTER: [number, number] = [-4.42, 36.72];
const CITY_ZOOM = 12;
const MAP_HEIGHT = 220;

export const MAP_NOT_CONFIGURED =
  'The map is not configured. You can still report the incident with your location.';
export const MAP_LOAD_FAILED =
  'The map could not load. You can still report the incident with your location.';

/**
 * Self-hosted OpenStreetMap base map (ADR-0016). The style comes from `EXPO_PUBLIC_MAP_STYLE_URL`
 * and loads the tiles through `pmtiles://`. A map that is missing or fails to load never blocks the
 * form around it: it shows a notice and offers to try again.
 */
export function BaseMap() {
  // Expo inlines EXPO_PUBLIC_* variables at build time, only when written out in full like this.
  const styleUrl = process.env.EXPO_PUBLIC_MAP_STYLE_URL;
  const [failed, setFailed] = useState(false);
  // A new key mounts a new native map, which loads the style again.
  const [attempt, setAttempt] = useState(0);

  if (!styleUrl) {
    return <Notice message={MAP_NOT_CONFIGURED} />;
  }

  if (failed) {
    return (
      <Notice message={MAP_LOAD_FAILED}>
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

  return (
    <View style={styles.container}>
      <Map
        key={attempt}
        style={styles.map}
        mapStyle={styleUrl}
        attribution={false}
        logo={false}
        onDidFailLoadingMap={() => setFailed(true)}
      >
        <Camera initialViewState={{ center: MALAGA_CENTER, zoom: CITY_ZOOM }} />
      </Map>
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
