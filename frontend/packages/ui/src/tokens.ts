/**
 * Design tokens as plain values, so the web (CSS-in-JS or inline styles) and React Native
 * (StyleSheet) read the same palette and spacing.
 */
export const colors = {
  primary: '#2563EB',
  background: '#FFFFFF',
  surface: '#F1F5F9',
  text: '#0F172A',
  textMuted: '#64748B',
  success: '#16A34A',
  warning: '#D97706',
  danger: '#DC2626',
} as const;

/** Spacing scale in pixels (web) or density-independent pixels (React Native). */
export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
} as const;

export const fontSizes = {
  body: 16,
  title: 24,
} as const;

export type ColorToken = keyof typeof colors;
