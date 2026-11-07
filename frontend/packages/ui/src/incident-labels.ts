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

/** Time zone of Málaga, where the incidents happen: dates are shown and chosen in it. */
const CITY_TIME_ZONE = 'Europe/Madrid';

const DATE_TIME = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: CITY_TIME_ZONE,
});

/** An instant of the API (UTC) as local time in Málaga, where the incidents happen. */
export function formatInstant(instant: string): string {
  return DATE_TIME.format(new Date(instant));
}

const ZONE_OFFSET = new Intl.DateTimeFormat('en-US', {
  timeZone: CITY_TIME_ZONE,
  timeZoneName: 'longOffset',
});

/**
 * The instant (UTC, RFC 3339) at which a day starts in Málaga, for a date filter of the API.
 *
 * @param day a calendar date as `YYYY-MM-DD`, the value of an `<input type="date">`
 */
export function startOfDay(day: string): string {
  const [year, month, date] = day.split('-').map(Number);
  const utcMidnight = Date.UTC(year!, month! - 1, date!);
  // Summer time changes at 01:00 UTC, so the offset at UTC midnight is also the offset at the
  // local midnight a few hours earlier.
  return new Date(utcMidnight - offsetMinutes(new Date(utcMidnight)) * 60_000).toISOString();
}

/** Minutes Málaga is ahead of UTC at an instant: 60 in winter, 120 in summer. */
function offsetMinutes(instant: Date): number {
  const name = ZONE_OFFSET.formatToParts(instant).find((part) => part.type === 'timeZoneName');
  const match = /GMT([+-])(\d{2}):(\d{2})/.exec(name?.value ?? '');
  // `longOffset` writes a zero offset as a bare "GMT".
  if (!match) {
    return 0;
  }
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === '-' ? -minutes : minutes;
}
