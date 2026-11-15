import { INCIDENT_STATUSES, type IncidentStatus } from './incident-status';
import type { Role } from './role';

/**
 * Who may make a transition (ADR-0020, "Who"). `OPERATOR` is a `MUNICIPAL_OPERATOR` or an
 * `ADMINISTRATOR`, who can do what an operator does; `ASSIGNED_TECHNICIAN` is the `TECHNICIAN` of the
 * active assignment of the incident, not any technician.
 */
export type Performer = 'OPERATOR' | 'ASSIGNED_TECHNICIAN';

/** The domain actions of ADR-0020, named as `IncidentTransition` in the backend. */
export type TransitionName =
  | 'INFORMATION_REQUEST'
  | 'VALIDATION'
  | 'REJECTION'
  | 'ASSIGNMENT'
  | 'ACCEPTANCE'
  | 'RESOLUTION'
  | 'CLOSURE'
  | 'REOPENING';

export interface IncidentTransition {
  readonly name: TransitionName;
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
 * The states no transition leaves (`REJECTED`, `CLOSED` in ADR-0020): for the citizen, the closed
 * incidents. Derived from the table, so a new transition out of a state reopens it here too.
 */
export const FINAL_STATUSES: readonly IncidentStatus[] = INCIDENT_STATUSES.filter(
  (status) => !INCIDENT_TRANSITIONS.some((transition) => transition.from.includes(status)),
);

/** The states that can still change, including `RESOLVED`, which an operator may reopen. */
export const OPEN_STATUSES: readonly IncidentStatus[] = INCIDENT_STATUSES.filter(
  (status) => !FINAL_STATUSES.includes(status),
);

/**
 * The user who would make the change. `isAssignedTechnician` tells whether they are the technician
 * of the incident's active assignment, which the role alone does not say.
 */
export interface TransitionActor {
  readonly role: Role;
  readonly isAssignedTechnician: boolean;
}

/**
 * The transitions the table lets `actor` make on an incident in `from`, in the order of the table.
 * The web shows one action panel for each of them.
 */
export function availableTransitions(
  from: IncidentStatus,
  actor: TransitionActor,
): readonly IncidentTransition[] {
  return INCIDENT_TRANSITIONS.filter(
    (transition) => transition.from.includes(from) && performs(transition.performer, actor),
  );
}

function performs(performer: Performer, actor: TransitionActor): boolean {
  switch (performer) {
    case 'OPERATOR':
      return actor.role === 'MUNICIPAL_OPERATOR' || actor.role === 'ADMINISTRATOR';
    case 'ASSIGNED_TECHNICIAN':
      return actor.role === 'TECHNICIAN' && actor.isAssignedTechnician;
  }
}
