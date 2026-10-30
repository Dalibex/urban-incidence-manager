import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { StyleSheet } from 'react-native';

/** Layout shared by the shell's screens. */
export const screenStyles = StyleSheet.create({
  flex: {
    flex: 1,
    backgroundColor: colors.background,
  },
  form: {
    padding: spacing.md,
    gap: spacing.md,
  },
  centered: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  screen: {
    flex: 1,
    backgroundColor: colors.background,
    padding: spacing.md,
    gap: spacing.sm,
  },
  title: {
    color: colors.text,
    fontSize: fontSizes.title,
    fontWeight: 'bold',
  },
  body: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
  note: {
    color: colors.textMuted,
    fontSize: fontSizes.body,
  },
  actions: {
    marginTop: spacing.md,
    gap: spacing.sm,
  },
});
