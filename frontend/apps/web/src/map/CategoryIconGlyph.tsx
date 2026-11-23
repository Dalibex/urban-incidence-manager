import { createElement } from 'react';

import { ICON_STROKE_WIDTH, iconShapes, type IconKey } from './category-icons';

interface CategoryIconGlyphProps {
  icon: IconKey;
  className?: string;
}

/**
 * The icon of a category inside the page, in the colour of the text around it. It is hidden from
 * screen readers: the name of the category always goes beside it in words (#267).
 */
export function CategoryIconGlyph({ icon, className }: CategoryIconGlyphProps) {
  return (
    <svg
      className={className}
      aria-hidden="true"
      focusable="false"
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={ICON_STROKE_WIDTH}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {iconShapes(icon).map(([tag, attributes], index) =>
        createElement(tag, { key: index, ...attributes }),
      )}
    </svg>
  );
}
