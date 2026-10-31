import { colors, fontSizes, spacing } from '@urbanpulse/ui';

/**
 * Publishes the shared design tokens as CSS custom properties (`--color-primary`, `--space-md`...),
 * so the CSS modules read the same values as the mobile app instead of copying them.
 */
export function applyDesignTokens(root: HTMLElement): void {
  const groups = { color: colors, space: spacing, 'font-size': fontSizes };
  for (const [prefix, tokens] of Object.entries(groups)) {
    for (const [name, value] of Object.entries(tokens)) {
      const cssValue = typeof value === 'number' ? `${value}px` : value;
      root.style.setProperty(`--${prefix}-${toKebabCase(name)}`, cssValue);
    }
  }
}

function toKebabCase(name: string): string {
  return name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
}
