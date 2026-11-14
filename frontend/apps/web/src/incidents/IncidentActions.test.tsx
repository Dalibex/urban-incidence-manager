import { screen, waitFor, within } from '@testing-library/react';
import type { components } from '@urbanpulse/api-client';
import {
  availableTransitions,
  INCIDENT_STATUSES,
  INCIDENT_TRANSITIONS,
  type IncidentStatus,
  type TransitionName,
} from '@urbanpulse/domain';
import { describe, expect, it } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];

/**
 * The detail shows a panel for each transition that `availableTransitions` allows the user, and
 * no other (#277): a transition of `@urbanpulse/domain` without its panel makes these tests fail.
 * The transition table itself is checked against ADR-0020 in `@urbanpulse/domain`.
 */

const INCIDENT: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'REPORTED',
  priority: 'HIGH',
  priorityReason: 'The street is dark near a school.',
  category: { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' },
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: 'Calle Larios 12',
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-03T08:00:00Z',
  closedAt: null,
  assignment: null,
  version: 4,
};

/** The title of the form of each transition, as the user reads it. */
const PANEL_TITLES: Record<TransitionName, (status: IncidentStatus) => string> = {
  INFORMATION_REQUEST: () => 'Request information',
  VALIDATION: () => 'Validate',
  REJECTION: () => 'Reject',
  ASSIGNMENT: (status) => (status === 'ASSIGNED' ? 'Reassign' : 'Assign'),
  ACCEPTANCE: () => 'Accept the work',
  RESOLUTION: () => 'Resolve',
  CLOSURE: () => 'Verify and close',
  REOPENING: () => 'Reopen',
};

/**
 * The order the detail shows its panels in: the operator classifies a report before deciding on it,
 * and validating comes first among those decisions (#189). It is not the order of the domain table.
 */
const DETAIL_ORDER = [
  'Classification',
  'Validate',
  'Request information',
  'Reject',
  'Assign',
  'Reassign',
  'Accept the work',
  'Resolve',
  'Verify and close',
  'Reopen',
];

function inDetailOrder(titles: readonly string[]): string[] {
  return [...titles].sort((a, b) => DETAIL_ORDER.indexOf(a) - DETAIL_ORDER.indexOf(b));
}

const EMPTY_PAGE = { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } };

function api(incident: Incident) {
  return fakeApi({
    [`GET /incidents/${incident.id}`]: () => json(200, incident),
    [`GET /incidents/${incident.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${incident.id}/classification-changes`]: () => json(200, EMPTY_PAGE),
    [`GET /incidents/${incident.id}/comments`]: () => json(200, EMPTY_PAGE),
    [`GET /incidents/${incident.id}/attachments`]: () => json(200, []),
    'GET /categories': () => json(200, [INCIDENT.category]),
    'GET /departments': () => json(200, []),
    'GET /technicians': () => json(200, []),
  });
}

/** The forms of the actions region, without the comment, which every status has. */
function actionForms(): string[] {
  const actions = within(screen.getByRole('region', { name: 'Actions' }));
  return actions
    .queryAllByRole('form')
    .map((form) => form.getAttribute('aria-labelledby'))
    .map((titleId) => document.getElementById(titleId ?? '')?.textContent ?? '')
    .filter((title) => title !== 'Comment');
}

const OPERATOR_ROLES = ['MUNICIPAL_OPERATOR', 'ADMINISTRATOR'] as const;
const FINAL: readonly IncidentStatus[] = ['REJECTED', 'CLOSED'];

const cases = INCIDENT_STATUSES.flatMap((status) =>
  [...OPERATOR_ROLES, 'TECHNICIAN' as const].map((role) => [status, role] as const),
);

describe('the action panels follow the transition table (#277)', () => {
  it('reaches every transition with some staff role, so the cases below cover them all', () => {
    const covered = new Set(
      cases.flatMap(([status, role]) =>
        availableTransitions(status, { role, isAssignedTechnician: role === 'TECHNICIAN' }).map(
          ({ name }) => name,
        ),
      ),
    );

    expect([...covered].sort()).toEqual(INCIDENT_TRANSITIONS.map(({ name }) => name).sort());
  });

  it.each(cases)('on %s, %s sees exactly the panels of its transitions', async (status, role) => {
    const incident = { ...INCIDENT, status };
    renderApp(incidentPath(incident.id), { role, fetch: api(incident) });
    await screen.findByRole('heading', { level: 1, name: incident.title });

    const transitions = availableTransitions(status, {
      role,
      isAssignedTechnician: role === 'TECHNICIAN',
    }).map(({ name }) => PANEL_TITLES[name](status));
    const classification =
      role !== 'TECHNICIAN' && !FINAL.includes(status) ? ['Classification'] : [];
    const expected = inDetailOrder([...classification, ...transitions]);

    await waitFor(() => expect(actionForms()).toEqual(expected));
    if (expected.length === 0) {
      expect(screen.getByText('Only comments are available for this incident.')).toBeDefined();
    }
  });
});
