import type { IncidentStatus } from '@urbanpulse/domain';

/**
 * One color per status (#288), so a technician's map of `ASSIGNED` and `IN_PROGRESS` incidents is
 * not all one color. Each one has at least 4.5:1 contrast with `background` and `surface` (WCAG AA
 * for text) and its own hue, but a color never carries the meaning alone: every badge, marker
 * summary and legend also writes the status.
 */
const STATUS_COLOR: Record<IncidentStatus, string> = {
  REPORTED: '#B45309', // amber, 5.0:1 on white
  VALIDATED: '#0E7490', // cyan, 5.4:1
  REJECTED: '#BE185D', // pink, 6.0:1
  ASSIGNED: '#6D28D9', // violet, 7.1:1
  IN_PROGRESS: '#1D4ED8', // blue, 6.7:1
  RESOLVED: '#15803D', // green, 5.0:1
  REOPENED: '#B91C1C', // red, 6.5:1
  CLOSED: '#475569', // slate, 7.6:1
};

/** Color used to show an incident status as a badge, on web and mobile alike. */
export function statusColor(status: IncidentStatus): string {
  return STATUS_COLOR[status];
}
