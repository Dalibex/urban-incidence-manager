import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { statusColor } from './status-color';
import { colors } from './tokens';

describe('statusColor', () => {
  it('gives every status a color from the palette', () => {
    const palette = Object.values(colors) as string[];
    for (const status of INCIDENT_STATUSES) {
      expect(palette).toContain(statusColor(status));
    }
  });
});
