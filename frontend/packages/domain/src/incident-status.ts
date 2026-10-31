/**
 * Lifecycle states of an incident, in the same spelling as the database (`incident.status`) and the
 * API. The allowed changes between them are in `incident-transition.ts`.
 */
export const INCIDENT_STATUSES = [
  'REPORTED',
  'VALIDATED',
  'REJECTED',
  'ASSIGNED',
  'IN_PROGRESS',
  'RESOLVED',
  'REOPENED',
  'CLOSED',
] as const;

export type IncidentStatus = (typeof INCIDENT_STATUSES)[number];

/** Narrows an untrusted value, such as an API response field, to a known status. */
export function isIncidentStatus(value: unknown): value is IncidentStatus {
  return typeof value === 'string' && (INCIDENT_STATUSES as readonly string[]).includes(value);
}
