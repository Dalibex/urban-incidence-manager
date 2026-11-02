import { INCIDENT_TRANSITIONS, type IncidentStatus } from '@urbanpulse/domain';

/** Name of each status as people read it, on web and mobile alike. */
export const STATUS_LABELS: Record<IncidentStatus, string> = {
  REPORTED: 'Reported',
  VALIDATED: 'Validated',
  REJECTED: 'Rejected',
  ASSIGNED: 'Assigned',
  IN_PROGRESS: 'In progress',
  RESOLVED: 'Resolved',
  REOPENED: 'Reopened',
  CLOSED: 'Closed',
};

const EVENT_LABELS: Record<string, string> = {
  INFORMATION_REQUEST: 'Information requested',
  VALIDATION: 'Validated',
  REJECTION: 'Rejected',
  ASSIGNMENT: 'Assigned',
  ACCEPTANCE: 'Work started',
  RESOLUTION: 'Resolved',
  CLOSURE: 'Closed',
  REOPENING: 'Reopened',
};

/**
 * What happened in a history entry, named after the transition of ADR-0020 that made it. The status
 * alone does not tell an information request (stays `REPORTED`) or a reassignment (stays `ASSIGNED`).
 */
export function historyEventLabel(previous: IncidentStatus | null, next: IncidentStatus): string {
  if (previous === null) {
    return 'Reported';
  }
  if (previous === 'ASSIGNED' && next === 'ASSIGNED') {
    return 'Reassigned';
  }
  const transition = INCIDENT_TRANSITIONS.find(
    (candidate) => candidate.to === next && candidate.from.includes(previous),
  );
  return (transition && EVENT_LABELS[transition.name]) ?? STATUS_LABELS[next];
}

/** A value the API sends as `null` because it is unknown or not looked up yet (docs/api.md). */
export const NOT_AVAILABLE = 'Not available';

const DATE_TIME = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Europe/Madrid',
});

/** An instant of the API (UTC) as local time in Málaga, where the incidents happen. */
export function formatInstant(instant: string): string {
  return DATE_TIME.format(new Date(instant));
}
