import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];

/**
 * The operator closes or reopens a resolved incident from its detail (#52), with the case of #75
 * it covers: T12 screen failures. T1, both actions on the running system, is manual.
 */

const REASON = 'The neighbours say the lamp went off again the same night.';

const RESOLVED: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'RESOLVED',
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
  updatedAt: '2026-11-04T08:00:00Z',
  closedAt: null,
  version: 6,
};

const CLOSED: Incident = {
  ...RESOLVED,
  status: 'CLOSED',
  closedAt: '2026-11-05T10:00:00Z',
  version: 7,
};
const REOPENED: Incident = { ...RESOLVED, status: 'REOPENED', version: 7 };

interface Server {
  incident: Incident;
  detailReads: number;
  closed: unknown[];
  reopened: unknown[];
}

type Answer = () => Response | Promise<Response>;

function api(server: Server, answers: { closure?: Answer; reopening?: Answer } = {}) {
  return fakeApi({
    'GET /categories': () => json(200, [RESOLVED.category]),
    [`GET /incidents/${RESOLVED.id}`]: () => {
      server.detailReads++;
      return json(200, server.incident);
    },
    [`GET /incidents/${RESOLVED.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${RESOLVED.id}/attachments`]: () => json(200, []),
    [`POST /incidents/${RESOLVED.id}/closure`]: async (request) => {
      server.closed.push(await request.json());
      if (answers.closure) {
        return answers.closure();
      }
      server.incident = CLOSED;
      return json(200, CLOSED);
    },
    [`POST /incidents/${RESOLVED.id}/reopening`]: async (request) => {
      server.reopened.push(await request.json());
      if (answers.reopening) {
        return answers.reopening();
      }
      server.incident = REOPENED;
      return json(200, REOPENED);
    },
  });
}

function newServer(incident: Incident = RESOLVED): Server {
  return { incident, detailReads: 0, closed: [], reopened: [] };
}

async function actions() {
  return within(await screen.findByRole('region', { name: 'Actions' }));
}

const CONFLICT = () =>
  json(409, { status: 409, code: 'CONCURRENT_MODIFICATION', title: 'Conflict' });

describe('close or reopen a resolved incident (#52)', () => {
  it('offers both actions on a resolved incident, next to the classification', async () => {
    renderApp(incidentPath(RESOLVED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(newServer()) });

    const panel = await actions();
    expect(panel.getByRole('form', { name: 'Classification' })).toBeDefined();
    expect(panel.getByRole('form', { name: 'Verify and close' })).toBeDefined();
    expect(panel.getByRole('form', { name: 'Reopen' })).toBeDefined();
  });

  it('is offered to administrators too', async () => {
    renderApp(incidentPath(RESOLVED.id), { role: 'ADMINISTRATOR', fetch: api(newServer()) });

    expect((await actions()).getByRole('form', { name: 'Verify and close' })).toBeDefined();
  });

  it('is not offered on an incident that is not resolved', async () => {
    renderApp(incidentPath(RESOLVED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(newServer({ ...RESOLVED, status: 'IN_PROGRESS' })),
    });

    const panel = await actions();
    expect(panel.getByRole('form', { name: 'Classification' })).toBeDefined();
    expect(panel.queryByRole('form', { name: 'Verify and close' })).toBeNull();
    expect(panel.queryByRole('form', { name: 'Reopen' })).toBeNull();
  });

  it('closes with the version it read, and then offers nothing more', async () => {
    const server = newServer();
    renderApp(incidentPath(RESOLVED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    await userEvent.click((await actions()).getByRole('button', { name: 'Close the incident' }));

    expect(await screen.findByText('Incident closed.')).toBeDefined();
    expect(await screen.findByText('Only comments are available for this incident.')).toBeDefined();
    expect(server.closed).toEqual([{ version: RESOLVED.version }]);
    expect(server.reopened).toEqual([]);
    // No form follows: the focus goes to the message instead of the page.
    await waitFor(() => expect(document.activeElement?.textContent).toBe('Incident closed.'));
  });

  it('asks for the reason before reopening, then sends it with the version', async () => {
    const server = newServer();
    renderApp(incidentPath(RESOLVED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const panel = await actions();

    await userEvent.click(panel.getByRole('button', { name: 'Reopen the incident' }));
    expect(await panel.findByText('Write why the work is not well done.')).toBeDefined();
    expect(server.reopened).toEqual([]);

    await userEvent.type(panel.getByLabelText('Reason for reopening'), REASON);
    await userEvent.click(panel.getByRole('button', { name: 'Reopen the incident' }));

    expect(await screen.findByText('Incident reopened: it can be assigned again.')).toBeDefined();
    expect(server.reopened).toEqual([{ version: RESOLVED.version, reason: REASON }]);
    // A reopened incident is no longer resolved: only the classification stays.
    await waitFor(() =>
      expect(screen.queryByRole('form', { name: 'Verify and close' })).toBeNull(),
    );
    expect(screen.queryByRole('form', { name: 'Reopen' })).toBeNull();
    expect(screen.getByRole('form', { name: 'Classification' })).toBeDefined();
  });

  // T12: a double click sends one request, nothing says success before the answer, and a 409
  // offers to reload the incident.
  it('sends one request on a double click, and offers to reload after a 409', async () => {
    const server = newServer();
    let respond: (response: Response) => void = () => {};
    renderApp(incidentPath(RESOLVED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, {
        closure: () => new Promise<Response>((resolve) => (respond = resolve)),
      }),
    });
    const panel = await actions();
    const close = within(panel.getByRole('form', { name: 'Verify and close' }));

    await userEvent.dblClick(close.getByRole('button', { name: 'Close the incident' }));
    expect(await close.findByRole('button', { name: 'Saving…' })).toHaveProperty('disabled', true);
    expect(screen.queryByText('Incident closed.')).toBeNull();
    expect(server.closed).toHaveLength(1);

    respond(CONFLICT());
    expect(
      await close.findByText(
        'The incident changed since you opened it. Reload it and check it before trying again.',
      ),
    ).toBeDefined();
    const reads = server.detailReads;
    await userEvent.click(close.getByRole('button', { name: 'Reload the incident' }));
    await waitFor(() => expect(server.detailReads).toBeGreaterThan(reads));
    expect(server.closed).toHaveLength(1);
  });

  // T12: the reason stays after a 409, so the operator does not write it again.
  it('keeps the reason after a 409 on reopening', async () => {
    const server = newServer();
    renderApp(incidentPath(RESOLVED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, { reopening: CONFLICT }),
    });
    const panel = await actions();

    await userEvent.type(panel.getByLabelText('Reason for reopening'), REASON);
    await userEvent.click(panel.getByRole('button', { name: 'Reopen the incident' }));

    expect(await panel.findByText(/The incident changed since you opened it/)).toBeDefined();
    expect(panel.getByLabelText<HTMLTextAreaElement>('Reason for reopening').value).toBe(REASON);
    expect(screen.queryByText('Incident reopened: it can be assigned again.')).toBeNull();
  });

  // T12: someone else already closed or reopened it.
  it('says when the incident no longer allows the action', async () => {
    renderApp(incidentPath(RESOLVED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(newServer(), {
        closure: () => json(409, { status: 409, code: 'INVALID_TRANSITION', title: 'Conflict' }),
      }),
    });

    await userEvent.click((await actions()).getByRole('button', { name: 'Close the incident' }));

    expect(
      await screen.findByText(
        'This incident no longer allows this action. Reload it to see its status.',
      ),
    ).toBeDefined();
  });

  // T12: no network, then a 5xx; the reason stays and the operator can send it again.
  it('offers to reopen again after a network failure or a 5xx', async () => {
    const server = newServer();
    const answers: Answer[] = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
      () => {
        server.incident = REOPENED;
        return json(200, REOPENED);
      },
    ];
    renderApp(incidentPath(RESOLVED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, { reopening: () => answers.shift()!() }),
    });
    const reopen = within((await actions()).getByRole('form', { name: 'Reopen' }));
    const send = reopen.getByRole('button', { name: 'Reopen the incident' });

    await userEvent.type(reopen.getByLabelText('Reason for reopening'), REASON);
    await userEvent.click(send);
    expect((await reopen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached',
    );
    expect(reopen.getByLabelText('Reason for reopening')).toHaveProperty('value', REASON);

    await userEvent.click(send);
    expect((await reopen.findByRole('alert')).textContent).toContain('could not be saved');
    expect(reopen.getByLabelText('Reason for reopening')).toHaveProperty('value', REASON);

    await userEvent.click(send);
    expect(await screen.findByText('Incident reopened: it can be assigned again.')).toBeDefined();
    expect(server.reopened).toEqual([
      { version: RESOLVED.version, reason: REASON },
      { version: RESOLVED.version, reason: REASON },
      { version: RESOLVED.version, reason: REASON },
    ]);
  });
});
