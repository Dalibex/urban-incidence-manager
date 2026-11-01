import type { IncidentStatus } from './incident-status';
import type { Role } from './role';

/**
 * Who may make a transition (ADR-0020, "Who"). `OPERATOR` is a `MUNICIPAL_OPERATOR` or an
 * `ADMINISTRATOR`, who can do what an operator does; `ASSIGNED_TECHNICIAN` is the `TECHNICIAN` of the
 * active assignment of the incident, not any technician.
 */
export type Performer = 'OPERATOR' | 'ASSIGNED_TECHNICIAN';

export interface IncidentTransition {
  readonly name: string;
  readonly from: readonly IncidentStatus[];
  readonly to: IncidentStatus;
  readonly performer: Performer;
}

/**
 * The transition table of ADR-0020, the same as `IncidentTransition` in the backend: each entry is
 * one domain action of the API, and a change that no entry describes is not allowed. The clients
 * use it only to show or hide actions; the API checks every change again.
 */
export const INCIDENT_TRANSITIONS: readonly IncidentTransition[] = [
  { name: 'INFORMATION_REQUEST', from: ['REPORTED'], to: 'REPORTED', performer: 'OPERATOR' },
  { name: 'VALIDATION', from: ['REPORTED'], to: 'VALIDATED', performer: 'OPERATOR' },
  { name: 'REJECTION', from: ['REPORTED'], to: 'REJECTED', performer: 'OPERATOR' },
  // Also the reassignment (ASSIGNED -> ASSIGNED).
  {
    name: 'ASSIGNMENT',
    from: ['VALIDATED', 'REOPENED', 'ASSIGNED'],
    to: 'ASSIGNED',
    performer: 'OPERATOR',
  },
  { name: 'ACCEPTANCE', from: ['ASSIGNED'], to: 'IN_PROGRESS', performer: 'ASSIGNED_TECHNICIAN' },
  { name: 'RESOLUTION', from: ['IN_PROGRESS'], to: 'RESOLVED', performer: 'ASSIGNED_TECHNICIAN' },
  { name: 'CLOSURE', from: ['RESOLVED'], to: 'CLOSED', performer: 'OPERATOR' },
  { name: 'REOPENING', from: ['RESOLVED'], to: 'REOPENED', performer: 'OPERATOR' },
];

/**
 * The user who would make the change. `isAssignedTechnician` tells whether they are the technician
 * of the incident's active assignment, which the role alone does not say.
 */
export interface TransitionActor {
  readonly role: Role;
  readonly isAssignedTechnician: boolean;
}

/** Whether the table allows `actor` to move an incident from `from` to `to`. */
export function canTransition(
  from: IncidentStatus,
  to: IncidentStatus,
  actor: TransitionActor,
): boolean {
  const transition = INCIDENT_TRANSITIONS.find(
    (candidate) => candidate.to === to && candidate.from.includes(from),
  );
  return transition !== undefined && performs(transition.performer, actor);
}

function performs(performer: Performer, actor: TransitionActor): boolean {
  switch (performer) {
    case 'OPERATOR':
      return actor.role === 'MUNICIPAL_OPERATOR' || actor.role === 'ADMINISTRATOR';
    case 'ASSIGNED_TECHNICIAN':
      return actor.role === 'TECHNICIAN' && actor.isAssignedTechnician;
  }
}
