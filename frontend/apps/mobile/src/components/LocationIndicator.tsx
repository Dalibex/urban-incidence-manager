import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';

import type { ReportedLocation } from '../location/report-location';
import { Button } from './Button';

export const PLACED_BY_HAND = 'Position chosen on the map.';

export interface LocationIndicatorProps {
  /** What the report will send; `null` when there is nothing to send yet. */
  location: ReportedLocation | null;
  /** True when the location comes from the GPS fix, false when the citizen chose it. */
  fromGps: boolean;
  isLoading: boolean;
  error: string | null;
  permissionDenied: boolean;
  onUseMyPosition: () => void;
  onOpenSettings: () => void;
}

/** Says where the position of the report comes from, and how to use the GPS when it is off. */
export function LocationIndicator({
  location,
  fromGps,
  isLoading,
  error,
  permissionDenied,
  onUseMyPosition,
  onOpenSettings,
}: LocationIndicatorProps) {
  if (isLoading) {
    return (
      <View style={styles.row} accessibilityRole="progressbar">
        <ActivityIndicator />
        <Text style={styles.text}>Getting your location…</Text>
      </View>
    );
  }

  return (
    <View style={styles.block}>
      {location !== null ? (
        <Text style={styles.text}>{fromGps ? gpsLine(location.accuracy) : PLACED_BY_HAND}</Text>
      ) : null}
      {error ? (
        <Text accessibilityLiveRegion="polite" style={styles.error}>
          {error}
        </Text>
      ) : null}
      {permissionDenied ? (
        <>
          <Text style={styles.text}>
            To start from your position, allow the location in the device settings.
          </Text>
          <Button label="Open settings" variant="secondary" onPress={onOpenSettings} />
        </>
      ) : (
        <Button label="Use my position" variant="secondary" onPress={onUseMyPosition} />
      )}
    </View>
  );
}

function gpsLine(accuracy: number | undefined): string {
  return `Your GPS position. Accuracy: ${
    accuracy !== undefined ? `${Math.round(accuracy)} m` : 'unknown'
  }.`;
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  block: {
    gap: spacing.sm,
  },
  text: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
  error: {
    color: colors.danger,
    fontSize: 14,
  },
});
