import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { statusColor } from './status-color';

describe('statusColor', () => {
  it('gives every status a color of its own', () => {
    const used = INCIDENT_STATUSES.map((status) => statusColor(status).toUpperCase());

    expect(new Set(used).size).toBe(INCIDENT_STATUSES.length);
  });
});
