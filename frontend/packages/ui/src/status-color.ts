import type { IncidentStatus } from '@urbanpulse/domain';

/**
 * One color per status (#288), so a technician's map of `ASSIGNED` and `IN_PROGRESS` incidents is
 * not all one color. They live here and not in `tokens.ts` because the palette has too few hues
 * for eight statuses. Each one has at least 4.5:1 contrast with `background` and `surface` (WCAG
 * AA for text), and every pair stays apart (CIE76 ΔE of 15 or more) for normal vision and under a
 * simulated deuteranopia and protanopia, the common color blindness. A color never carries the
 * meaning alone: every badge, marker summary and legend also writes the status.
 */
const STATUS_COLOR: Record<IncidentStatus, string> = {
  REPORTED: '#C2410C', // orange, 5.2:1 on white
  VALIDATED: '#0369A1', // sky, 5.9:1
  REJECTED: '#155E75', // dark cyan, 7.3:1
  ASSIGNED: '#6B21A8', // purple, 8.7:1
  IN_PROGRESS: '#2563EB', // blue, 5.2:1
  RESOLVED: '#166534', // green, 7.1:1
  REOPENED: '#BE123C', // rose, 6.3:1
  CLOSED: '#0F766E', // teal, 5.5:1
};

/** Color used to show an incident status as a badge, on web and mobile alike. */
export function statusColor(status: IncidentStatus): string {
  return STATUS_COLOR[status];
}
