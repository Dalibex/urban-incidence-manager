import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { colors, fontSizes, spacing, statusColor } from '@urbanpulse/ui';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View } from 'react-native';

/** Start screen of the skeleton: proves that the shared packages are wired into the mobile app. */
export default function App() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>UrbanPulse</Text>
      <Text style={styles.subtitle}>Mobile app for citizens.</Text>
      <View style={styles.statuses}>
        {INCIDENT_STATUSES.map((status) => (
          <Text key={status} style={[styles.badge, { backgroundColor: statusColor(status) }]}>
            {status}
          </Text>
        ))}
      </View>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.md,
  },
  title: {
    color: colors.text,
    fontSize: fontSizes.title,
    fontWeight: 'bold',
  },
  subtitle: {
    color: colors.textMuted,
    fontSize: fontSizes.body,
    marginVertical: spacing.sm,
  },
  statuses: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: spacing.sm,
  },
  badge: {
    color: colors.background,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: 4,
    overflow: 'hidden',
  },
});
