import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { MapKey } from './MapKey';

/** T3 and T7 of #303: the map key lists every status with its color and its name. */
describe('MapKey', () => {
  it('lists each status once, by name, with the color of its markers', () => {
    render(<MapKey open onToggle={() => {}} />);

    const statuses = within(screen.getAllByRole('list')[0]!).getAllByRole('listitem');
    expect(statuses.map((item) => item.textContent)).toEqual(
      INCIDENT_STATUSES.map((status) => STATUS_LABELS[status]),
    );
    statuses.forEach((item, index) => {
      const swatch = item.querySelector('span')!;
      // The swatch is decoration for a screen reader: the name already says the status.
      expect(swatch.getAttribute('aria-hidden')).toBe('true');
      expect(swatch.style.background).toBe(rgb(statusColor(INCIDENT_STATUSES[index]!)));
    });
  });
});

/** How jsdom reads back a `#RRGGBB` set on `style.background`. */
function rgb(hex: string): string {
  const [r, g, b] = [1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16));
  return `rgb(${r}, ${g}, ${b})`;
}
