import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { createQueryClient } from '../query-client';
import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];
type Assignment = components['schemas']['AssignmentResponse'];

/**
 * The operator assigns an incident from its detail, or reassigns it (#185), with the case of #72
 * it covers: T16 screen failures. T1, the incident in the technician's queue on the running
 * system, is manual.
 */

const LIGHTING = { id: 'd1e2f3a4-b5c6-4d7e-8f9a-0b1c2d3e4f5a', name: 'Street lighting service' };
const PARKS = { id: 'd2e3f4a5-b6c7-4d8e-9f0a-1b2c3d4e5f6a', name: 'Parks and gardens' };
const SERGIO = { id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', name: 'Sergio Molina' };
const LUCIA = { id: '8d3f5c2b-0e4a-4f9b-8c7d-2a1b3c4d5e6f', name: 'Lucía Martín' };

const VALIDATED: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'VALIDATED',
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
  version: 2,
};

function assigned(assignment: Partial<Assignment>, version = 3): Incident {
  return {
    ...VALIDATED,
    status: 'ASSIGNED',
    version,
    assignment: {
      id: 'e5f6a7b8-c9d0-4e1f-a2b3-c4d5e6f7a8b9',
      department: LIGHTING,
      technician: SERGIO,
      team: null,
      assignedAt: '2026-11-03T09:00:00Z',
      ...assignment,
    },
  };
}

interface Server {
  incident: Incident;
  detailReads: number;
  sent: unknown[];
}

type Answer = () => Response | Promise<Response>;

interface Answers {
  assignment?: Answer;
  technicians?: Answer;
}

function api(server: Server, answers: Answers = {}) {
  return fakeApi({
    'GET /categories': () => json(200, [VALIDATED.category]),
    'GET /departments': () => json(200, [PARKS, LIGHTING]),
    'GET /technicians': () => (answers.technicians ?? (() => json(200, [LUCIA, SERGIO])))(),
    [`GET /incidents/${VALIDATED.id}`]: () => {
      server.detailReads++;
      return json(200, server.incident);
    },
    [`PATCH /incidents/${VALIDATED.id}`]: async (request) => {
      const body = (await request.json()) as {
        version: number;
        priority: 'LOW';
        priorityReason: string;
      };
      if (body.version !== server.incident.version) {
        return CONFLICT();
      }
      server.incident = {
        ...server.incident,
        priority: body.priority,
        priorityReason: body.priorityReason,
        version: (server.incident.version ?? 0) + 1,
      };
      return json(200, server.incident);
    },
    [`GET /incidents/${VALIDATED.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${VALIDATED.id}/attachments`]: () => json(200, []),
    [`POST /incidents/${VALIDATED.id}/assignments`]: async (request) => {
      const body = (await request.json()) as {
        version: number;
        departmentId: string;
        technicianId?: string;
        team?: string;
      };
      server.sent.push(body);
      if (answers.assignment) {
        return answers.assignment();
      }
      // Like the API: a version that is not the current one is a change made meanwhile.
      if (body.version !== server.incident.version) {
        return CONFLICT();
      }
      server.incident = assigned(
        {
          department: [PARKS, LIGHTING].find((department) => department.id === body.departmentId),
          technician: [LUCIA, SERGIO].find((technician) => technician.id === body.technicianId),
          team: body.team ?? null,
        },
        (server.incident.version ?? 0) + 1,
      );
      if (body.technicianId === undefined) {
        server.incident.assignment!.technician = null;
      }
      return json(200, server.incident);
    },
  });
}

function newServer(incident: Incident = VALIDATED): Server {
  return { incident, detailReads: 0, sent: [] };
}

async function assignForm(name = 'Assign') {
  const actions = within(await screen.findByRole('region', { name: 'Actions' }));
  return within(await actions.findByRole('form', { name }));
}

/** Waits until the options have loaded, so a choice is not made on an empty list. */
async function choose(form: Awaited<ReturnType<typeof assignForm>>, label: string, name: string) {
  await form.findByRole('option', { name });
  await userEvent.selectOptions(form.getByLabelText(label), name);
}

const CONFLICT = () =>
  json(409, { status: 409, code: 'CONCURRENT_MODIFICATION', title: 'Conflict' });

describe('assign an incident from its detail (#185)', () => {
  it.each(['VALIDATED', 'REOPENED'] as const)(
    'offers the assignment on a %s incident, next to the classification',
    async (status) => {
      renderApp(incidentPath(VALIDATED.id), {
        role: 'MUNICIPAL_OPERATOR',
        fetch: api(newServer({ ...VALIDATED, status })),
      });

      const form = await assignForm();
      expect(screen.getByRole('form', { name: 'Classification' })).toBeDefined();
      expect(await form.findByRole('option', { name: 'Parks and gardens' })).toBeDefined();
      expect(await form.findByRole('option', { name: 'Sergio Molina' })).toBeDefined();
      expect(
        form.getByRole('option', { name: 'No technician, the department only' }),
      ).toBeDefined();
    },
  );

  it('is offered to administrators too', async () => {
    renderApp(incidentPath(VALIDATED.id), { role: 'ADMINISTRATOR', fetch: api(newServer()) });

    expect(await assignForm()).toBeDefined();
  });

  it.each(['REPORTED', 'IN_PROGRESS', 'RESOLVED'] as const)(
    'is not offered on a %s incident',
    async (status) => {
      renderApp(incidentPath(VALIDATED.id), {
        role: 'MUNICIPAL_OPERATOR',
        fetch: api(newServer({ ...VALIDATED, status })),
      });

      const actions = within(await screen.findByRole('region', { name: 'Actions' }));
      expect(await actions.findByRole('form', { name: 'Classification' })).toBeDefined();
      expect(actions.queryByRole('form', { name: 'Assign' })).toBeNull();
      expect(actions.queryByRole('form', { name: 'Reassign' })).toBeNull();
    },
  );

  it('asks for the department before sending anything', async () => {
    const server = newServer();
    renderApp(incidentPath(VALIDATED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const form = await assignForm();

    await userEvent.click(form.getByRole('button', { name: 'Assign the incident' }));

    expect(await form.findByText('Choose a department.')).toBeDefined();
    expect(form.getByLabelText('Department')).toHaveProperty('ariaInvalid', 'true');
    expect(server.sent).toEqual([]);
  });

  it('assigns with the version it read, the technician and the team, and then offers to reassign', async () => {
    const server = newServer();
    renderApp(incidentPath(VALIDATED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const form = await assignForm();

    await choose(form, 'Department', 'Street lighting service');
    await choose(form, 'Technician', 'Sergio Molina');
    await userEvent.type(form.getByLabelText('Team (optional)'), '  Night shift ');
    await userEvent.click(form.getByRole('button', { name: 'Assign the incident' }));

    expect(await screen.findByText('Incident assigned.')).toBeDefined();
    expect(server.sent).toEqual([
      {
        version: VALIDATED.version,
        departmentId: LIGHTING.id,
        technicianId: SERGIO.id,
        team: 'Night shift',
      },
    ]);
    const reassign = await assignForm('Reassign');
    expect(reassign.getByRole('button', { name: 'Reassign the incident' })).toHaveProperty(
      'disabled',
      true,
    );
  });

  it('assigns the department only, with no technician and no team', async () => {
    const server = newServer({ ...VALIDATED, status: 'REOPENED', version: 7 });
    renderApp(incidentPath(VALIDATED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const form = await assignForm();

    await choose(form, 'Department', 'Parks and gardens');
    await userEvent.click(form.getByRole('button', { name: 'Assign the incident' }));

    expect(await screen.findByText('Incident assigned.')).toBeDefined();
    expect(server.sent).toEqual([{ version: 7, departmentId: PARKS.id }]);
  });

  it('reassigns from the active assignment, once something changed', async () => {
    const server = newServer(assigned({ team: 'Night shift' }));
    renderApp(incidentPath(VALIDATED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const form = await assignForm('Reassign');
    const send = form.getByRole('button', { name: 'Reassign the incident' });

    await form.findByRole('option', { name: 'Lucía Martín' });
    expect(form.getByLabelText<HTMLSelectElement>('Department').value).toBe(LIGHTING.id);
    expect(form.getByLabelText<HTMLSelectElement>('Technician').value).toBe(SERGIO.id);
    expect(form.getByLabelText<HTMLInputElement>('Team (optional)').value).toBe('Night shift');
    expect(send).toHaveProperty('disabled', true);

    await choose(form, 'Technician', 'Lucía Martín');
    expect(send).toHaveProperty('disabled', false);
    await userEvent.click(send);

    expect(await screen.findByText('Incident reassigned.')).toBeDefined();
    expect(server.sent).toEqual([
      { version: 3, departmentId: LIGHTING.id, technicianId: LUCIA.id, team: 'Night shift' },
    ]);
  });

  it('keeps a technician who is no longer active as the current choice', async () => {
    const former = { id: '9e4a6d3c-1f5b-4a0c-9d8e-3b2c4d5e6f7a', name: 'Marta Former' };
    renderApp(incidentPath(VALIDATED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(newServer(assigned({ technician: former }))),
    });
    const form = await assignForm('Reassign');

    await form.findByRole('option', { name: 'Lucía Martín' });
    expect(form.getByLabelText<HTMLSelectElement>('Technician').value).toBe(former.id);
  });

  it('shows the field the API rejected next to it', async () => {
    renderApp(incidentPath(VALIDATED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(newServer(), {
        assignment: () =>
          json(422, {
            status: 422,
            title: 'Unprocessable',
            errors: [{ field: 'technicianId', message: 'The technician is not active.' }],
          }),
      }),
    });
    const form = await assignForm();

    await choose(form, 'Department', 'Street lighting service');
    await choose(form, 'Technician', 'Sergio Molina');
    await userEvent.click(form.getByRole('button', { name: 'Assign the incident' }));

    expect(await form.findByText('The technician is not active.')).toBeDefined();
    expect(form.getByLabelText('Technician')).toHaveProperty('ariaInvalid', 'true');
    expect(screen.queryByText('Incident assigned.')).toBeNull();
  });

  it('still assigns the department when the technicians cannot be loaded', async () => {
    const server = newServer();
    renderApp(incidentPath(VALIDATED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, { technicians: () => json(404, { status: 404, title: 'Not found' }) }),
    });
    const form = await assignForm();

    expect(
      await form.findByText(
        'The technicians could not be loaded. You can still assign the department only.',
      ),
    ).toBeDefined();
    await choose(form, 'Department', 'Parks and gardens');
    await userEvent.click(form.getByRole('button', { name: 'Assign the incident' }));

    expect(await screen.findByText('Incident assigned.')).toBeDefined();
    expect(server.sent).toEqual([{ version: VALIDATED.version, departmentId: PARKS.id }]);
  });

  // T16: a double click sends one request, nothing says success before the answer, and a 409
  // offers to reload the incident.
  it('sends one request on a double click, and offers to reload after a 409', async () => {
    const server = newServer();
    let respond: (response: Response) => void = () => {};
    renderApp(incidentPath(VALIDATED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, {
        assignment: () => new Promise<Response>((resolve) => (respond = resolve)),
      }),
    });
    const form = await assignForm();

    await choose(form, 'Department', 'Street lighting service');
    await userEvent.dblClick(form.getByRole('button', { name: 'Assign the incident' }));
    expect(await form.findByRole('button', { name: 'Saving…' })).toHaveProperty('disabled', true);
    expect(screen.queryByText('Incident assigned.')).toBeNull();
    expect(server.sent).toHaveLength(1);

    respond(CONFLICT());
    expect(
      await form.findByText(
        'The incident changed since you opened it. Reload it and check it before trying again.',
      ),
    ).toBeDefined();
    expect(form.getByLabelText<HTMLSelectElement>('Department').value).toBe(LIGHTING.id);
    const reads = server.detailReads;
    await userEvent.click(form.getByRole('button', { name: 'Reload the incident' }));
    await waitFor(() => expect(server.detailReads).toBeGreaterThan(reads));
    expect(server.sent).toHaveLength(1);
  });

  // The classification changes the version but not the assignment, so it is no conflict.
  it('assigns right after the operator saved the classification on the same page', async () => {
    const server = newServer();
    renderApp(incidentPath(VALIDATED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const classification = within(await screen.findByRole('form', { name: 'Classification' }));
    await userEvent.selectOptions(classification.getByLabelText('Priority'), 'LOW');
    const reason = classification.getByLabelText('Why this priority');
    await userEvent.clear(reason);
    await userEvent.type(reason, 'A side street.');
    await userEvent.click(classification.getByRole('button', { name: 'Save classification' }));
    expect(await classification.findByText('Classification saved.')).toBeDefined();

    const form = await assignForm();
    await choose(form, 'Department', 'Parks and gardens');
    await userEvent.click(form.getByRole('button', { name: 'Assign the incident' }));

    expect(await screen.findByText('Incident assigned.')).toBeDefined();
    // The version the classification answered with, one more than the one the page opened with.
    expect(server.sent).toEqual([{ version: 3, departmentId: PARKS.id }]);
  });

  it('does not undo a reassignment made meanwhile, and shows it after reloading', async () => {
    const server = newServer(assigned({}, 3));
    const queryClient = createQueryClient({ retryDelay: 0 });
    renderApp(incidentPath(VALIDATED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server),
      queryClient,
    });
    const form = await assignForm('Reassign');
    await form.findByRole('option', { name: 'Lucía Martín' });

    // Another operator reassigns it, and the page reads the incident again, as it does when the
    // operator comes back to the tab. The form still shows what it read first.
    server.incident = assigned({ department: PARKS, technician: LUCIA }, 4);
    const reads = server.detailReads;
    await queryClient.invalidateQueries();
    await waitFor(() => expect(server.detailReads).toBeGreaterThan(reads));
    expect(form.getByLabelText<HTMLSelectElement>('Department').value).toBe(LIGHTING.id);

    await userEvent.type(form.getByLabelText('Team (optional)'), 'Night shift');
    await userEvent.click(form.getByRole('button', { name: 'Reassign the incident' }));

    expect(
      await form.findByText(
        'The incident changed since you opened it. Reload it and check it before trying again.',
      ),
    ).toBeDefined();
    expect(server.sent).toEqual([
      { version: 3, departmentId: LIGHTING.id, technicianId: SERGIO.id, team: 'Night shift' },
    ]);
    expect(server.incident.assignment?.department).toEqual(PARKS);

    // What the operator did not change shows the other reassignment; their team stays.
    await userEvent.click(form.getByRole('button', { name: 'Reload the incident' }));
    await waitFor(() =>
      expect(form.getByLabelText<HTMLSelectElement>('Department').value).toBe(PARKS.id),
    );
    expect(form.getByLabelText<HTMLSelectElement>('Technician').value).toBe(LUCIA.id);
    expect(form.getByLabelText<HTMLInputElement>('Team (optional)').value).toBe('Night shift');

    await userEvent.click(form.getByRole('button', { name: 'Reassign the incident' }));

    expect(await screen.findByText('Incident reassigned.')).toBeDefined();
    expect(server.sent[1]).toEqual({
      version: 4,
      departmentId: PARKS.id,
      technicianId: LUCIA.id,
      team: 'Night shift',
    });
  });

  // T16: no network, then a 5xx; the choices stay and the operator can send them again.
  it('offers to assign again after a network failure or a 5xx', async () => {
    const server = newServer();
    const answers: Answer[] = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
    ];
    renderApp(incidentPath(VALIDATED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, {
        assignment: answers.length > 0 ? () => answers.shift()!() : undefined,
      }),
    });
    const form = await assignForm();
    const send = form.getByRole('button', { name: 'Assign the incident' });

    await choose(form, 'Department', 'Street lighting service');
    await choose(form, 'Technician', 'Sergio Molina');
    await userEvent.click(send);
    expect((await form.findByRole('alert')).textContent).toContain('The server cannot be reached');
    expect(form.getByLabelText<HTMLSelectElement>('Technician').value).toBe(SERGIO.id);

    await userEvent.click(send);
    expect((await form.findByRole('alert')).textContent).toContain('could not be saved');
    expect(form.getByLabelText<HTMLSelectElement>('Technician').value).toBe(SERGIO.id);

    expect(server.sent).toEqual([
      { version: VALIDATED.version, departmentId: LIGHTING.id, technicianId: SERGIO.id },
      { version: VALIDATED.version, departmentId: LIGHTING.id, technicianId: SERGIO.id },
    ]);
  });
});
