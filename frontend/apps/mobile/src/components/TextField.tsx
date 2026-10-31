import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { StyleSheet, Text, TextInput, View, type TextInputProps } from 'react-native';

import { MIN_TOUCH_SIZE } from './Button';

interface TextFieldProps extends Omit<TextInputProps, 'style'> {
  label: string;
  hint?: string;
}

/** Text input with a visible label, which screen readers also read as the field's name. */
export function TextField({ label, hint, ...input }: TextFieldProps) {
  return (
    <View style={styles.field}>
      {/* Label and hint are hidden from screen readers: the input already reads both. */}
      <Text style={styles.label} accessibilityElementsHidden importantForAccessibility="no">
        {label}
      </Text>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={hint}
        placeholderTextColor={colors.textMuted}
        style={styles.input}
        {...input}
      />
      {hint ? (
        <Text style={styles.hint} accessibilityElementsHidden importantForAccessibility="no">
          {hint}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  field: {
    gap: spacing.xs,
  },
  label: {
    color: colors.text,
    fontSize: fontSizes.body,
    fontWeight: '600',
  },
  input: {
    minHeight: MIN_TOUCH_SIZE,
    paddingHorizontal: spacing.sm,
    borderWidth: 1,
    borderColor: colors.textMuted,
    borderRadius: 8,
    color: colors.text,
    fontSize: fontSizes.body,
  },
  hint: {
    color: colors.textMuted,
    fontSize: 14,
  },
});
