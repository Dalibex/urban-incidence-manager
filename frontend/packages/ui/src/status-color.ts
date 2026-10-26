import type { IncidentStatus } from '@urbanpulse/domain';

import { colors, type ColorToken } from './tokens';

const STATUS_COLOR: Record<IncidentStatus, ColorToken> = {
  REPORTED: 'warning',
  VALIDATED: 'primary',
  REJECTED: 'textMuted',
  ASSIGNED: 'primary',
  IN_PROGRESS: 'primary',
  RESOLVED: 'success',
  REOPENED: 'danger',
  CLOSED: 'textMuted',
};

/** Color used to show an incident status as a badge, on web and mobile alike. */
export function statusColor(status: IncidentStatus): string {
  return colors[STATUS_COLOR[status]];
}
