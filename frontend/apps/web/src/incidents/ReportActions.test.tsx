import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];

/**
 * The operator validates, rejects or asks for more information on a reported incident from its
 * detail (#189), with the case of #71 it covers: T14 screen failures. T1 (the three actions on the
 * running system) and T2 (what the citizen sees on the mobile app) are manual.
 */

const STREET_LIGHTING = { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' };

const REPORTED: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'REPORTED',
  priority: 'HIGH',
  priorityReason: 'The street is dark near a school.',
  category: STREET_LIGHTING,
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: 'Calle Larios 12',
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
  version: 2,
};

const UNCLASSIFIED: Incident = {
  ...REPORTED,
  category: null,
  priority: null,
  priorityReason: null,
};

const VALIDATED: Incident = { ...REPORTED, status: 'VALIDATED', version: 3 };

type Action = 'validation' | 'rejection' | 'information-requests';
type Answer = () => Response | Promise<Response>;

interface Server {
  incident: Incident;
  detailReads: number;
  sent: Record<Action, unknown[]>;
}

function api(server: Server, answers: Partial<Record<Action, Answer>> = {}) {
  const action = (name: Action, after: Incident) => async (request: Request) => {
    server.sent[name].push(await request.json());
    const answer = answers[name];
    if (answer) {
      return answer();
    }
    server.incident = after;
    return json(200, after);
  };
  return fakeApi({
    'GET /categories': () => json(200, [STREET_LIGHTING]),
    [`GET /incidents/${REPORTED.id}`]: () => {
      server.detailReads++;
      return json(200, server.incident);
    },
    [`GET /incidents/${REPORTED.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${REPORTED.id}/attachments`]: () => json(200, []),
    [`POST /incidents/${REPORTED.id}/validation`]: action('validation', VALIDATED),
  });
}

function newServer(incident: Incident = REPORTED): Server {
  return {
    incident,
    detailReads: 0,
    sent: { validation: [], rejection: [], 'information-requests': [] },
  };
}

async function actions() {
  return within(await screen.findByRole('region', { name: 'Actions' }));
}

async function form(name: string) {
  return within((await actions()).getByRole('form', { name }));
}

const CONFLICT = () =>
  json(409, { status: 409, code: 'CONCURRENT_MODIFICATION', title: 'Conflict' });

describe('validate a reported incident (#189)', () => {
  it.each(['MUNICIPAL_OPERATOR', 'ADMINISTRATOR'] as const)(
    'offers to validate next to the classification to %s',
    async (role) => {
      renderApp(incidentPath(REPORTED.id), { role, fetch: api(newServer()) });

      const panel = await actions();
      expect(panel.getByRole('form', { name: 'Classification' })).toBeDefined();
      expect(panel.getByRole('form', { name: 'Validate' })).toBeDefined();
    },
  );

  it('is not offered once the incident is no longer reported', async () => {
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(newServer(VALIDATED)),
    });

    const panel = await actions();
    expect(panel.getByRole('form', { name: 'Classification' })).toBeDefined();
    expect(panel.queryByRole('form', { name: 'Validate' })).toBeNull();
  });

  it('waits for the category and the priority before validating', async () => {
    const server = newServer(UNCLASSIFIED);
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const validate = await form('Validate');

    expect(
      validate.getByText('Set its category and priority in Classification and save them first.'),
    ).toBeDefined();
    const button = validate.getByRole('button', { name: 'Validate the incident' });
    expect(button).toHaveProperty('disabled', true);
    await userEvent.click(button);
    expect(server.sent.validation).toEqual([]);
  });

  it('validates with the version it read, and leaves only the classification', async () => {
    const server = newServer();
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    await userEvent.click(
      (await form('Validate')).getByRole('button', { name: 'Validate the incident' }),
    );

    expect(await screen.findByText('Incident validated: it can be assigned now.')).toBeDefined();
    expect(server.sent.validation).toEqual([{ version: REPORTED.version }]);
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Validate' })).toBeNull());
    expect(screen.getByRole('form', { name: 'Classification' })).toBeDefined();
  });

  // T14: a double click sends one request, nothing says success before the answer, and a 409
  // offers to reload the incident.
  it('sends one request on a double click, and offers to reload after a 409', async () => {
    const server = newServer();
    let respond: (response: Response) => void = () => {};
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, {
        validation: () => new Promise<Response>((resolve) => (respond = resolve)),
      }),
    });
    const validate = await form('Validate');

    await userEvent.dblClick(validate.getByRole('button', { name: 'Validate the incident' }));
    expect(await validate.findByRole('button', { name: 'Saving…' })).toHaveProperty(
      'disabled',
      true,
    );
    expect(screen.queryByText('Incident validated: it can be assigned now.')).toBeNull();
    expect(server.sent.validation).toHaveLength(1);

    respond(CONFLICT());
    expect(
      await validate.findByText(
        'The incident changed since you opened it. Reload it and check it before trying again.',
      ),
    ).toBeDefined();
    const reads = server.detailReads;
    await userEvent.click(validate.getByRole('button', { name: 'Reload the incident' }));
    await waitFor(() => expect(server.detailReads).toBeGreaterThan(reads));
    expect(server.sent.validation).toHaveLength(1);
  });

  // T14: no network, then a 5xx; the operator can validate again.
  it('offers to validate again after a network failure or a 5xx', async () => {
    const server = newServer();
    const answers: Answer[] = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
      () => {
        server.incident = VALIDATED;
        return json(200, VALIDATED);
      },
    ];
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, { validation: () => answers.shift()!() }),
    });
    const validate = await form('Validate');
    const send = validate.getByRole('button', { name: 'Validate the incident' });

    await userEvent.click(send);
    expect((await validate.findByRole('alert')).textContent).toContain(
      'The server cannot be reached',
    );

    await userEvent.click(send);
    expect((await validate.findByRole('alert')).textContent).toContain('could not be saved');

    await userEvent.click(send);
    expect(await screen.findByText('Incident validated: it can be assigned now.')).toBeDefined();
    expect(server.sent.validation).toHaveLength(3);
  });
});
