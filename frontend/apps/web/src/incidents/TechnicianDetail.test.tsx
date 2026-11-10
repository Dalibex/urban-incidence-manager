import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { incidentPath, PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];

/**
 * The technician's detail (#187, steps 2 and 3), with the cases of #74 it covers: T15 screen
 * failures and T16 the detail itself. It opens from the work queue and leads back to it, has no
 * operator actions, and offers Accept on an assigned incident and Resolve on one in progress.
 */

const NOTES = 'Replaced the lamp and checked it at night.';

const ASSIGNED: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'ASSIGNED',
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
  version: 4,
};

const IN_PROGRESS: Incident = { ...ASSIGNED, status: 'IN_PROGRESS', version: 5 };
const RESOLVED: Incident = { ...ASSIGNED, status: 'RESOLVED', version: 6 };

const OTHER_INCIDENT_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';

interface Server {
  incident: Incident;
  accepted: unknown[];
  resolved: unknown[];
}

function api(
  server: Server,
  resolveAnswer: () => Response | Promise<Response> = () => {
    server.incident = RESOLVED;
    return json(200, RESOLVED);
  },
) {
  return fakeApi({
    'GET /incidents': () =>
      json(200, {
        content: [
          {
            id: server.incident.id,
            title: server.incident.title,
            status: server.incident.status,
            priority: server.incident.priority ?? null,
            category: server.incident.category,
            reportedAt: server.incident.reportedAt,
            updatedAt: server.incident.updatedAt,
          },
        ],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
      }),
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
    [`GET /incidents/${ASSIGNED.id}`]: () => json(200, server.incident),
    [`GET /incidents/${ASSIGNED.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${ASSIGNED.id}/classification-changes`]: () =>
      json(200, { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } }),
    [`GET /incidents/${ASSIGNED.id}/attachments`]: () => json(200, []),
    [`GET /incidents/${OTHER_INCIDENT_ID}`]: () =>
      json(404, { status: 404, code: 'NOT_FOUND', title: 'Not Found' }),
    [`POST /incidents/${ASSIGNED.id}/acceptance`]: async (request) => {
      server.accepted.push(await request.json());
      server.incident = IN_PROGRESS;
      return json(200, IN_PROGRESS);
    },
    [`POST /incidents/${ASSIGNED.id}/resolution`]: async (request) => {
      server.resolved.push(await request.json());
      return resolveAnswer();
    },
  });
}

function newServer(incident: Incident = ASSIGNED): Server {
  return { incident, accepted: [], resolved: [] };
}

async function actions() {
  return within(await screen.findByRole('region', { name: 'Actions' }));
}

describe("technician's incident detail (#187)", () => {
  // T16
  it('opens from the work queue and leads back to it as it was left', async () => {
    const queue = `${PATHS.workQueue}?status=ASSIGNED&page=0`;
    const router = renderApp(queue, { role: 'TECHNICIAN', fetch: api(newServer()) });

    await userEvent.click(await screen.findByRole('link', { name: `Open ${ASSIGNED.title}` }));
    expect(await screen.findByRole('heading', { level: 1, name: ASSIGNED.title })).toBeDefined();
    expect(router.state.location.pathname).toBe(incidentPath(ASSIGNED.id));

    await userEvent.click(screen.getByRole('link', { name: 'Back to the work queue' }));
    const { pathname, search } = router.state.location;
    expect(pathname + search).toBe(queue);
  });

  // T16
  it('has no operator actions', async () => {
    renderApp(incidentPath(ASSIGNED.id), { role: 'TECHNICIAN', fetch: api(newServer()) });

    const panel = await actions();
    expect(panel.getByRole('form', { name: 'Accept the work' })).toBeDefined();
    expect(panel.queryByRole('form', { name: 'Classification' })).toBeNull();
  });

  it('accepts with the version it read, then offers to resolve with the focus on the notes', async () => {
    const server = newServer();
    renderApp(incidentPath(ASSIGNED.id), { role: 'TECHNICIAN', fetch: api(server) });

    await userEvent.click((await actions()).getByRole('button', { name: 'Accept' }));

    expect(await screen.findByText('Incident accepted: it is now in progress.')).toBeDefined();
    expect(await screen.findByRole('form', { name: 'Resolve' })).toBeDefined();
    expect(server.accepted).toEqual([{ version: ASSIGNED.version }]);
    // The Accept button is gone: the focus goes on to the next form instead of the page.
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByLabelText('Resolution notes')),
    );
  });

  it('shows only the message of the latest action', async () => {
    const server = newServer();
    renderApp(incidentPath(ASSIGNED.id), {
      role: 'TECHNICIAN',
      fetch: api(server, () =>
        json(409, { status: 409, code: 'CONCURRENT_MODIFICATION', title: 'Conflict' }),
      ),
    });

    await userEvent.click((await actions()).getByRole('button', { name: 'Accept' }));
    expect(await screen.findByText('Incident accepted: it is now in progress.')).toBeDefined();

    await userEvent.type(await screen.findByLabelText('Resolution notes'), NOTES);
    await userEvent.click(screen.getByRole('button', { name: 'Mark as resolved' }));

    expect(await screen.findByText(/The incident changed since you opened it/)).toBeDefined();
    expect(screen.queryByText('Incident accepted: it is now in progress.')).toBeNull();
  });

  it('resolves with the notes, and asks for them before sending anything', async () => {
    const server = newServer(IN_PROGRESS);
    renderApp(incidentPath(ASSIGNED.id), { role: 'TECHNICIAN', fetch: api(server) });
    const panel = await actions();

    await userEvent.click(panel.getByRole('button', { name: 'Mark as resolved' }));
    expect(await panel.findByText('Write what was done.')).toBeDefined();
    expect(server.resolved).toEqual([]);

    await userEvent.type(panel.getByLabelText('Resolution notes'), NOTES);
    await userEvent.click(panel.getByRole('button', { name: 'Mark as resolved' }));

    expect(await screen.findByText('Incident resolved.')).toBeDefined();
    expect(await screen.findByText('Only comments are available for this incident.')).toBeDefined();
    expect(server.resolved).toEqual([{ version: IN_PROGRESS.version, reason: NOTES }]);
    // No form follows: the focus goes to the message instead of the page.
    await waitFor(() => expect(document.activeElement?.textContent).toBe('Incident resolved.'));
  });

  // T15: a double click, no success before the answer, and a 409.
  it('sends one request on a double click, and keeps the notes after a 409', async () => {
    const server = newServer(IN_PROGRESS);
    let respond: (response: Response) => void = () => {};
    renderApp(incidentPath(ASSIGNED.id), {
      role: 'TECHNICIAN',
      fetch: api(server, () => new Promise<Response>((resolve) => (respond = resolve))),
    });
    const panel = await actions();

    await userEvent.type(panel.getByLabelText('Resolution notes'), NOTES);
    await userEvent.dblClick(panel.getByRole('button', { name: 'Mark as resolved' }));
    expect(await panel.findByRole('button', { name: 'Saving…' })).toHaveProperty('disabled', true);
    expect(screen.queryByText('Incident resolved.')).toBeNull();
    expect(server.resolved).toHaveLength(1);

    respond(json(409, { status: 409, code: 'CONCURRENT_MODIFICATION', title: 'Conflict' }));
    expect(
      await panel.findByText(
        'The incident changed since you opened it. Reload it and check it before trying again.',
      ),
    ).toBeDefined();
    expect(panel.getByRole('button', { name: 'Reload the incident' })).toBeDefined();
    expect(panel.getByLabelText<HTMLTextAreaElement>('Resolution notes').value).toBe(NOTES);
    expect(server.resolved).toHaveLength(1);
  });

  // T15: no network, then a 5xx; the notes stay and the technician can send them again.
  it('offers to resolve again after a network failure or a 5xx', async () => {
    const server = newServer(IN_PROGRESS);
    const answers: (() => Response)[] = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
      () => {
        server.incident = RESOLVED;
        return json(200, RESOLVED);
      },
    ];
    renderApp(incidentPath(ASSIGNED.id), {
      role: 'TECHNICIAN',
      fetch: api(server, () => answers.shift()!()),
    });
    const panel = await actions();
    const resolve = panel.getByRole('button', { name: 'Mark as resolved' });

    await userEvent.type(panel.getByLabelText('Resolution notes'), NOTES);
    await userEvent.click(resolve);
    expect((await panel.findByRole('alert')).textContent).toContain('The server cannot be reached');
    expect(panel.getByLabelText('Resolution notes')).toHaveProperty('value', NOTES);

    await userEvent.click(resolve);
    expect((await panel.findByRole('alert')).textContent).toContain('could not be saved');
    expect(panel.getByLabelText('Resolution notes')).toHaveProperty('value', NOTES);

    await userEvent.click(resolve);
    expect(await screen.findByText('Incident resolved.')).toBeDefined();
    expect(server.resolved).toEqual([
      { version: IN_PROGRESS.version, reason: NOTES },
      { version: IN_PROGRESS.version, reason: NOTES },
      { version: IN_PROGRESS.version, reason: NOTES },
    ]);
  });

  // T16
  it('shows "not found" for an incident not assigned to them, with the way back', async () => {
    renderApp(incidentPath(OTHER_INCIDENT_ID), { role: 'TECHNICIAN', fetch: api(newServer()) });

    expect(await screen.findByRole('heading', { name: 'Incident not found' })).toBeDefined();
    expect(screen.getByRole('link', { name: 'Back to the work queue' })).toBeDefined();
  });
});
