import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { STATUS_LABELS } from '@urbanpulse/ui';
import { render, screen } from '@testing-library/react-native';

import { StatusBadge } from './StatusBadge';

/** T7 of #303: in *My reports* the color is never the only cue of a status. */
describe('StatusBadge', () => {
  it.each(INCIDENT_STATUSES)('writes %s as text next to its color', async (status) => {
    await render(<StatusBadge status={status} />);

    expect(screen.getByText(STATUS_LABELS[status])).toBeOnTheScreen();
  });
});
