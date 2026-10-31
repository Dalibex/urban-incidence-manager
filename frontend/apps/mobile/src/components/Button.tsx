import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { Pressable, StyleSheet, Text } from 'react-native';

interface ButtonProps {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'link';
  disabled?: boolean;
}

/** Touch target of at least 48 dp, the Android minimum (Apple asks for 44 pt). */
export const MIN_TOUCH_SIZE = 48;

export function Button({ label, onPress, variant = 'primary', disabled = false }: ButtonProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        styles[variant],
        (pressed || disabled) && styles.dimmed,
      ]}
    >
      <Text style={[styles.label, variant === 'primary' ? styles.onPrimary : styles.onSecondary]}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: MIN_TOUCH_SIZE,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: 8,
  },
  primary: {
    backgroundColor: colors.primary,
  },
  secondary: {
    borderWidth: 1,
    borderColor: colors.textMuted,
    backgroundColor: colors.background,
  },
  link: {
    backgroundColor: 'transparent',
  },
  dimmed: {
    opacity: 0.6,
  },
  label: {
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  onPrimary: {
    color: colors.background,
  },
  onSecondary: {
    color: colors.primary,
  },
});
