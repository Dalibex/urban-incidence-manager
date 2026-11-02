import type { IncidentStatus } from '@urbanpulse/domain';
import { colors, spacing, STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import { StyleSheet, Text, View } from 'react-native';

/** The status by name, with its shared color as a mark: the color never carries the meaning alone. */
export function StatusBadge({ status }: { status: IncidentStatus }) {
  return (
    <View style={styles.badge}>
      <View style={[styles.dot, { backgroundColor: statusColor(status) }]} />
      <Text style={styles.label}>{STATUS_LABELS[status]}</Text>
    </View>
  );
}

const DOT_SIZE = 10;

const styles = StyleSheet.create({
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  dot: {
    width: DOT_SIZE,
    height: DOT_SIZE,
    borderRadius: DOT_SIZE / 2,
  },
  label: {
    color: colors.text,
    fontWeight: '600',
  },
});
