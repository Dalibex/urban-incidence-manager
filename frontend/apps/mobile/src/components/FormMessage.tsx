import { colors, fontSizes } from '@urbanpulse/ui';
import { useEffect } from 'react';
import { AccessibilityInfo, StyleSheet, Text } from 'react-native';

interface FormMessageProps {
  message: string | null;
  tone?: 'error' | 'info';
}

/**
 * Message above a form. Android announces it through the live region; iOS has no live regions, so
 * the message is also announced when it changes.
 */
export function FormMessage({ message, tone = 'error' }: FormMessageProps) {
  useEffect(() => {
    if (message) {
      AccessibilityInfo.announceForAccessibility(message);
    }
  }, [message]);

  if (!message) {
    return null;
  }
  return (
    <Text accessibilityLiveRegion="polite" style={[styles.message, styles[tone]]}>
      {message}
    </Text>
  );
}

const styles = StyleSheet.create({
  message: {
    fontSize: fontSizes.body,
  },
  error: {
    color: colors.danger,
  },
  info: {
    color: colors.text,
  },
});
