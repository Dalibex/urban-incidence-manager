import { describe, expect, it } from 'vitest';

import { INCIDENT_STATUSES, type IncidentStatus } from './incident-status';
import { canTransition, type TransitionActor } from './incident-transition';
import { ROLES } from './role';

type Who = 'OPERATOR' | 'ASSIGNED_TECHNICIAN';

// ADR-0020, "Transition table", written out again so a change in INCIDENT_TRANSITIONS that the ADR
// does not have makes these tests fail: the 10 allowed pairs; the other 54 are forbidden.
const ADR_0020: readonly (readonly [IncidentStatus, IncidentStatus, Who])[] = [
  ['REPORTED', 'REPORTED', 'OPERATOR'],
  ['REPORTED', 'VALIDATED', 'OPERATOR'],
  ['REPORTED', 'REJECTED', 'OPERATOR'],
  ['VALIDATED', 'ASSIGNED', 'OPERATOR'],
  ['REOPENED', 'ASSIGNED', 'OPERATOR'],
  ['ASSIGNED', 'ASSIGNED', 'OPERATOR'],
  ['ASSIGNED', 'IN_PROGRESS', 'ASSIGNED_TECHNICIAN'],
  ['IN_PROGRESS', 'RESOLVED', 'ASSIGNED_TECHNICIAN'],
  ['RESOLVED', 'CLOSED', 'OPERATOR'],
  ['RESOLVED', 'REOPENED', 'OPERATOR'],
];

function allowedBy(who: Who | undefined, actor: TransitionActor): boolean {
  switch (who) {
    case 'OPERATOR':
      return actor.role === 'MUNICIPAL_OPERATOR' || actor.role === 'ADMINISTRATOR';
    case 'ASSIGNED_TECHNICIAN':
      return actor.role === 'TECHNICIAN' && actor.isAssignedTechnician;
    case undefined:
      return false;
  }
}

const pairs = INCIDENT_STATUSES.flatMap((from) =>
  INCIDENT_STATUSES.map((to) => [from, to] as const),
);
const actors: TransitionActor[] = ROLES.flatMap((role) => [
  { role, isAssignedTechnician: false },
  { role, isAssignedTechnician: true },
]);

describe('canTransition', () => {
  it.each(pairs)('%s -> %s is allowed only to whom ADR-0020 says', (from, to) => {
    const who = ADR_0020.find(([adrFrom, adrTo]) => adrFrom === from && adrTo === to)?.[2];

    for (const actor of actors) {
      expect(
        canTransition(from, to, actor),
        `${actor.role}, assigned ${actor.isAssignedTechnician}`,
      ).toBe(allowedBy(who, actor));
    }
  });

  it('lets only the assigned technician accept and resolve, not any technician', () => {
    const otherTechnician = { role: 'TECHNICIAN', isAssignedTechnician: false } as const;
    const assignedTechnician = { role: 'TECHNICIAN', isAssignedTechnician: true } as const;

    expect(canTransition('ASSIGNED', 'IN_PROGRESS', otherTechnician)).toBe(false);
    expect(canTransition('ASSIGNED', 'IN_PROGRESS', assignedTechnician)).toBe(true);
    expect(canTransition('IN_PROGRESS', 'RESOLVED', otherTechnician)).toBe(false);
    expect(canTransition('IN_PROGRESS', 'RESOLVED', assignedTechnician)).toBe(true);
  });

  it('keeps REJECTED and CLOSED final', () => {
    const administrator = { role: 'ADMINISTRATOR', isAssignedTechnician: false } as const;

    for (const to of INCIDENT_STATUSES) {
      expect(canTransition('REJECTED', to, administrator)).toBe(false);
      expect(canTransition('CLOSED', to, administrator)).toBe(false);
    }
  });
});
