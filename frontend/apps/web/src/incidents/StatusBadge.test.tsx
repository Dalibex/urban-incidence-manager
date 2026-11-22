import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { StatusBadge } from './StatusBadge';

/** T7 of #303: the color is never the only cue of a status. */
describe('StatusBadge', () => {
  it.each(INCIDENT_STATUSES)('writes %s as text next to its color', (status) => {
    render(<StatusBadge status={status} />);

    const badge = screen.getByText(STATUS_LABELS[status]);
    expect(badge.textContent).toBe(STATUS_LABELS[status]);
    expect(badge.style.getPropertyValue('--status-color')).toBe(statusColor(status));
  });
});
