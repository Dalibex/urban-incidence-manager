import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { incidentPath, PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];
type IncidentSummary = components['schemas']['IncidentSummaryResponse'];
type UpdateIncidentRequest = components['schemas']['UpdateIncidentRequest'];

/**
 * Tests of #70 for the classification panel of the incident detail (#47): T13 screen failures, T14
 * double submission and T15 data refreshed. They go through the real route table with a fake API.
 */

const LIGHTING = { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' };
const ROADS = { id: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e', name: 'Roads and pavements' };
const CATEGORIES = [
  { ...ROADS, description: null },
  { ...LIGHTING, description: null },
];
const REASON = 'The street is dark near a school.';

const INCIDENT: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'REPORTED',
  priority: null,
  priorityReason: null,
  category: null,
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

const CLASSIFIED: Incident = {
  ...INCIDENT,
  category: LIGHTING,
  priority: 'HIGH',
  priorityReason: REASON,
  updatedAt: '2026-11-02T10:30:00Z',
  version: 3,
};

function summaryOf(incident: Incident): IncidentSummary {
  return {
    id: incident.id,
    title: incident.title,
    status: incident.status,
    priority: incident.priority ?? null,
    category: incident.category,
    reportedAt: incident.reportedAt,
    updatedAt: incident.updatedAt,
  };
}

interface Server {
  incident: Incident;
  sent: UpdateIncidentRequest[];
  inboxReads: number;
  detailReads: number;
}

/** A fake API whose incident changes when the PATCH answers with `answer`. */
function api(
  server: Server,
  answer: (body: UpdateIncidentRequest) => Response | Promise<Response> = () => {
    server.incident = CLASSIFIED;
    return json(200, CLASSIFIED);
  },
) {
  return fakeApi({
    'GET /incidents': () => {
      server.inboxReads++;
      return json(200, {
        content: [summaryOf(server.incident)],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
      });
    },
    [`GET /incidents/${INCIDENT.id}`]: () => {
      server.detailReads++;
      return json(200, server.incident);
    },
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    'GET /categories': () => json(200, CATEGORIES),
    [`PATCH /incidents/${INCIDENT.id}`]: async (request) => {
      const body = (await request.json()) as UpdateIncidentRequest;
      server.sent.push(body);
      return answer(body);
    },
  });
}

function newServer(incident: Incident = INCIDENT): Server {
  return { incident, sent: [], inboxReads: 0, detailReads: 0 };
}

async function panel() {
  return within(await screen.findByRole('form', { name: 'Classification' }));
}

async function fillIn() {
  const form = await panel();
  await screen.findByRole('option', { name: LIGHTING.name });
  await userEvent.selectOptions(form.getByLabelText('Category'), LIGHTING.id);
  await userEvent.selectOptions(form.getByLabelText('Priority'), 'HIGH');
  await userEvent.type(form.getByLabelText('Why this priority'), REASON);
  return form;
}

function reportFact(label: string): string {
  const report = screen.getByRole('region', { name: 'Report' });
  const term = within(report).getByText(label, { selector: 'dt' });
  return term.nextElementSibling?.textContent ?? '';
}

describe('classification panel', () => {
  it('sends only what changed, with the version, and shows it saved without reloading', async () => {
    const server = newServer();
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    const form = await fillIn();
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));

    expect((await form.findByRole('status')).textContent).toContain('Classification saved.');
    expect(server.sent).toEqual([
      { version: 2, categoryId: LIGHTING.id, priority: 'HIGH', priorityReason: REASON },
    ]);
    // T15: the detail shows the new values from the answer, without reading it again.
    expect(reportFact('Category')).toBe(LIGHTING.name);
    expect(reportFact('Priority')).toBe('High');
    expect(reportFact('Why this priority')).toBe(REASON);
    expect(server.detailReads).toBe(1);
    // Nothing left to save.
    expect(form.getByRole('button', { name: 'Save classification' })).toHaveProperty(
      'disabled',
      true,
    );
  });

  it('sends a new justification with the priority it belongs to', async () => {
    const server = newServer(CLASSIFIED);
    renderApp(incidentPath(INCIDENT.id), {
      role: 'ADMINISTRATOR',
      fetch: api(server, () => json(200, { ...CLASSIFIED, version: 4 })),
    });

    const form = await panel();
    const reason = form.getByLabelText('Why this priority');
    await userEvent.clear(reason);
    await userEvent.type(reason, 'Two schools on the street.');
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));

    await waitFor(() =>
      expect(server.sent).toEqual([
        { version: 3, priority: 'HIGH', priorityReason: 'Two schools on the street.' },
      ]),
    );
  });

  // T15: the inbox shows the new values without reloading the page.
  it('refreshes the inbox after saving', async () => {
    const server = newServer();
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

    const row = (await screen.findByText(INCIDENT.title)).closest('tr')!;
    await userEvent.click(within(row).getByRole('link', { name: /manage/i }));
    const form = await fillIn();
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));
    await form.findByText('Classification saved.');
    await userEvent.click(screen.getByRole('link', { name: 'Back to the inbox' }));

    const table = await screen.findByRole('table');
    expect(await within(table).findByText(LIGHTING.name)).toBeDefined();
    expect(within(table).getByText('High')).toBeDefined();
    expect(server.inboxReads).toBe(2);
  });

  // T13: 409, the incident changed meanwhile.
  it('asks to reload after a 409 and keeps what the operator typed', async () => {
    const server = newServer();
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, () => {
        server.incident = { ...INCIDENT, version: 5, updatedAt: '2026-11-02T10:40:00Z' };
        return json(409, { status: 409, code: 'CONCURRENT_MODIFICATION' });
      }),
    });

    const form = await fillIn();
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));

    expect((await form.findByRole('alert')).textContent).toContain('The incident changed');
    expect(form.queryByText('Classification saved.')).toBeNull();
    expect(form.getByLabelText('Why this priority')).toHaveProperty('value', REASON);
    expect(form.getByLabelText('Category')).toHaveProperty('value', LIGHTING.id);

    await userEvent.click(form.getByRole('button', { name: 'Reload the incident' }));
    await waitFor(() => expect(server.detailReads).toBe(2));
    // The next save sends the version just read.
    server.incident = { ...INCIDENT, version: 5 };
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));
    await waitFor(() => expect(server.sent.at(-1)?.version).toBe(5));
  });

  // T13: no network, then a 5xx; the form keeps its values and can be sent again.
  it('offers to save again after a network failure or a 5xx', async () => {
    const server = newServer();
    const answers: (() => Response)[] = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
      () => {
        server.incident = CLASSIFIED;
        return json(200, CLASSIFIED);
      },
    ];
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, () => answers.shift()!()),
    });

    const form = await fillIn();
    const save = form.getByRole('button', { name: 'Save classification' });
    await userEvent.click(save);
    expect((await form.findByRole('alert')).textContent).toContain('The server cannot be reached');
    expect(form.getByLabelText('Why this priority')).toHaveProperty('value', REASON);

    await userEvent.click(save);
    expect((await form.findByRole('alert')).textContent).toContain('could not be saved');
    expect(form.getByLabelText('Priority')).toHaveProperty('value', 'HIGH');

    await userEvent.click(save);
    expect((await form.findByRole('status')).textContent).toContain('Classification saved.');
    expect(server.sent).toHaveLength(3);
  });

  // T13: no success before the answer. T14: a double click sends one request.
  it('says nothing is saved until the API answers, and sends one request per save', async () => {
    const server = newServer();
    let respond: (response: Response) => void = () => {};
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, () => new Promise<Response>((resolve) => (respond = resolve))),
    });

    const form = await fillIn();
    await userEvent.dblClick(form.getByRole('button', { name: 'Save classification' }));

    expect(await form.findByRole('button', { name: 'Saving…' })).toHaveProperty('disabled', true);
    expect(form.queryByText('Classification saved.')).toBeNull();
    expect(server.sent).toHaveLength(1);

    server.incident = CLASSIFIED;
    respond(json(200, CLASSIFIED));
    expect(await form.findByText('Classification saved.')).toBeDefined();
    expect(server.sent).toHaveLength(1);
  });

  it('shows the message of an invalid field next to it', async () => {
    const server = newServer();
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, () =>
        json(422, {
          status: 422,
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'priorityReason', message: 'A priority needs its justification.' }],
        }),
      ),
    });

    const form = await panel();
    await screen.findByRole('option', { name: LIGHTING.name });
    await userEvent.selectOptions(form.getByLabelText('Priority'), 'LOW');
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));

    expect(await form.findByText('A priority needs its justification.')).toBeDefined();
    expect(form.getByLabelText('Why this priority').getAttribute('aria-invalid')).toBe('true');
  });

  it('is not offered on a rejected or closed incident', async () => {
    for (const status of ['REJECTED', 'CLOSED'] as const) {
      const server = newServer({ ...CLASSIFIED, status });
      renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });

      expect(await screen.findByText('No actions are available for this incident.')).toBeDefined();
      expect(screen.queryByRole('form', { name: 'Classification' })).toBeNull();
      document.body.innerHTML = '';
    }
  });
});
