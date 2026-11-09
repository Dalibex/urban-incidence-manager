import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];
type ClassificationChange = components['schemas']['ClassificationChangeResponse'];

/**
 * Tests of #170 for the timeline of the incident detail on the web (#169): T4, T6 to T8, T10,
 * T11, T13 and T15. They go through the real route table with a fake API.
 */

const OPERATOR = { id: 'b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e', name: 'Lucía Martín' };
const ADMIN = { id: 'c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f', name: 'Jorge Ruiz' };
const CITIZEN = { id: 'd3e4f5a6-b7c8-4d9e-8f0a-2b3c4d5e6f7a', name: 'Ana García' };
const LIGHTING = { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' };

const INCIDENT: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'VALIDATED',
  priority: 'HIGH',
  priorityReason: 'Dark crossing near a school.',
  category: LIGHTING,
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: 'Calle Larios 12',
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  reportedAt: '2026-11-02T09:00:00Z',
  updatedAt: '2026-11-02T11:00:00Z',
  closedAt: null,
  version: 3,
};

const REPORTED: StatusChange = {
  id: '10000000-0000-4000-8000-000000000001',
  instant: '2026-11-02T09:00:00Z',
  previousStatus: null,
  newStatus: 'REPORTED',
  reason: null,
  actor: { ...CITIZEN, role: 'CITIZEN' },
  visibleToCitizen: true,
};

const INFORMATION_REQUEST: StatusChange = {
  id: '10000000-0000-4000-8000-000000000002',
  instant: '2026-11-02T09:30:00Z',
  previousStatus: 'REPORTED',
  newStatus: 'REPORTED',
  reason: 'Which lamp post is it?',
  actor: { ...OPERATOR, role: 'MUNICIPAL_OPERATOR' },
  visibleToCitizen: true,
};

const VALIDATED: StatusChange = {
  id: '10000000-0000-4000-8000-000000000003',
  instant: '2026-11-02T11:00:00Z',
  previousStatus: 'REPORTED',
  newStatus: 'VALIDATED',
  reason: 'Checked with the lighting team.',
  actor: { ...ADMIN, role: 'ADMINISTRATOR' },
  visibleToCitizen: false,
};

const CLASSIFIED: ClassificationChange = {
  id: '20000000-0000-4000-8000-000000000001',
  instant: '2026-11-02T10:00:00Z',
  actor: OPERATOR,
  before: { category: null, priority: null, priorityReason: null },
  after: { category: LIGHTING, priority: 'MEDIUM', priorityReason: 'Dark street.' },
};

const RECLASSIFIED: ClassificationChange = {
  id: '20000000-0000-4000-8000-000000000002',
  instant: '2026-11-02T10:30:00Z',
  actor: ADMIN,
  before: { priority: 'MEDIUM', priorityReason: 'Dark street.' },
  after: { priority: 'HIGH', priorityReason: 'Dark crossing near a school.' },
};

interface Server {
  history: () => Response;
  classification: (page: number) => Response;
  classificationReads: number;
}

function pageOf(content: ClassificationChange[], number = 0, totalPages = 1) {
  return json(200, {
    content,
    page: { number, size: 100, totalElements: content.length, totalPages },
  });
}

function server(
  history: StatusChange[],
  changes: ClassificationChange[],
  overrides: Partial<Server> = {},
): Server {
  return {
    history: () => json(200, history),
    classification: () => pageOf(changes),
    classificationReads: 0,
    ...overrides,
  };
}

function api(state: Server) {
  return fakeApi({
    [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => state.history(),
    [`GET /incidents/${INCIDENT.id}/classification-changes`]: (request) => {
      state.classificationReads++;
      return state.classification(Number(new URL(request.url).searchParams.get('page')));
    },
    'GET /categories': () => json(200, [{ ...LIGHTING, description: null }]),
  });
}

async function timeline() {
  const history = await screen.findByRole('region', { name: 'History' });
  return within(history);
}

/** The text of each entry of the timeline, in the order a screen reader reads them. */
async function entries(): Promise<string[]> {
  const list = (await timeline()).getByRole('list');
  return within(list)
    .getAllByRole('listitem')
    .map((item) => item.textContent ?? '');
}

function problem(status: number) {
  return json(status, { status, title: 'Server error' });
}

describe('incident timeline', () => {
  it('shows staff every change in order, with its actor, role and the internal mark (#170 T4, T13)', async () => {
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server([REPORTED, INFORMATION_REQUEST, VALIDATED], [RECLASSIFIED, CLASSIFIED])),
    });

    await waitFor(async () => expect(await entries()).toHaveLength(5));
    const [reported, informationRequest, classified, reclassified, validated] = await entries();

    expect(reported).toContain('Reported');
    expect(reported).toContain('By Ana García, citizen');
    expect(informationRequest).toContain('Information requested');
    expect(informationRequest).toContain('Which lamp post is it?');
    expect(classified).toContain('Classification changed');
    expect(classified).toContain('By Lucía Martín');
    expect(classified).toContain('Category: Street lighting (was Not classified)');
    expect(classified).toContain('Priority: Medium (was Not set)');
    expect(reclassified).toContain('By Jorge Ruiz');
    expect(reclassified).toContain('Priority: High (was Medium)');
    expect(reclassified).toContain(
      'Why this priority: Dark crossing near a school. (was Dark street.)',
    );
    expect(reclassified).not.toContain('Category');
    expect(validated).toContain('Validated');
    expect(validated).toContain('By Jorge Ruiz, administrator');
    expect(validated).toContain('Internal');
    expect(validated).toContain('Checked with the lighting team.');
    expect(informationRequest).not.toContain('Internal');
  });

  it('shows the date and time of each entry as a machine-readable time', async () => {
    renderApp(incidentPath(INCIDENT.id), {
      role: 'ADMINISTRATOR',
      fetch: api(server([REPORTED], [])),
    });

    const list = await (await timeline()).findByRole('list');
    const time = list.querySelector('time');
    expect(time?.getAttribute('dateTime')).toBe(REPORTED.instant);
    expect(time?.textContent).toBe('2 Nov 2026, 10:00');
  });

  it('shows a new incident as one entry, "Reported", with no empty state (#170 T7)', async () => {
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server([REPORTED], [])),
    });

    await waitFor(async () => expect(await entries()).toHaveLength(1));
    expect((await entries())[0]).toContain('Reported');
    await waitFor(async () =>
      expect((await timeline()).queryByText('Loading the classification changes…')).toBeNull(),
    );
    expect((await timeline()).queryByRole('alert')).toBeNull();
  });

  it('shows a reassignment and an information request as events (#170 T8)', async () => {
    const reassigned: StatusChange = {
      ...VALIDATED,
      id: '10000000-0000-4000-8000-000000000009',
      previousStatus: 'ASSIGNED',
      newStatus: 'ASSIGNED',
      visibleToCitizen: true,
    };
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server([REPORTED, INFORMATION_REQUEST, reassigned], [])),
    });

    await waitFor(async () => expect(await entries()).toHaveLength(3));
    const [, informationRequest, reassignment] = await entries();
    expect(informationRequest).toContain('Information requested');
    expect(reassignment).toContain('Reassigned');
  });

  it('keeps the same order for entries of the same instant (#170 T11)', async () => {
    const instant = '2026-11-02T10:00:00Z';
    const first = { ...INFORMATION_REQUEST, instant };
    const second = { ...VALIDATED, instant };
    renderApp(incidentPath(INCIDENT.id), {
      role: 'MUNICIPAL_OPERATOR',
      // The API sends them the other way round, and the classification change at the same instant.
      fetch: api(server([REPORTED, second, first], [{ ...CLASSIFIED, instant }])),
    });

    await waitFor(async () => expect(await entries()).toHaveLength(4));
    const [, one, two, three] = await entries();
    expect(one).toContain('Information requested');
    expect(two).toContain('Validated');
    expect(three).toContain('Classification changed');
  });

  it('reads every page of the classification changes', async () => {
    const changes = Array.from({ length: 101 }, (_, index) => ({
      ...RECLASSIFIED,
      id: `30000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    }));
    const state = server([REPORTED], [], {
      classification: (page) =>
        pageOf(page === 0 ? changes.slice(0, 100) : changes.slice(100), page, 2),
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(state) });

    await waitFor(async () => expect(await entries()).toHaveLength(102));
    expect(state.classificationReads).toBe(2);
  });

  it('keeps the status changes when the classification changes fail, and tries again (#170 T15)', async () => {
    let failing = true;
    const state = server([REPORTED, VALIDATED], [], {
      classification: () => (failing ? problem(503) : pageOf([CLASSIFIED])),
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(state) });

    const history = await timeline();
    const alert = await history.findByRole('alert');
    expect(alert.textContent).toContain('The classification changes could not be loaded.');
    expect(await entries()).toHaveLength(2);

    failing = false;
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    await waitFor(async () => expect(await entries()).toHaveLength(3));
    expect(history.queryByRole('alert')).toBeNull();
  });

  it('says the server cannot be reached when the network fails on the classification changes', async () => {
    const state = server([REPORTED], [], {
      classification: () => {
        throw new TypeError('Failed to fetch');
      },
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(state) });

    const alert = await (await timeline()).findByRole('alert');
    expect(alert.textContent).toContain('The server cannot be reached.');
    expect(await entries()).toHaveLength(1);
  });

  it('shows a classification saved from the detail once, without reloading the page (#170 T10)', async () => {
    const changes = [CLASSIFIED];
    const fetch = fakeApi({
      [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
      [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, [REPORTED]),
      [`GET /incidents/${INCIDENT.id}/classification-changes`]: () => pageOf([...changes]),
      'GET /categories': () => json(200, [{ ...LIGHTING, description: null }]),
      [`PATCH /incidents/${INCIDENT.id}`]: () => {
        changes.push({ ...RECLASSIFIED, before: { priority: 'HIGH' }, after: { priority: 'LOW' } });
        return json(200, { ...INCIDENT, priority: 'LOW', version: 4 });
      },
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });
    await waitFor(async () => expect(await entries()).toHaveLength(2));

    const form = within(await screen.findByRole('form', { name: 'Classification' }));
    await userEvent.selectOptions(form.getByLabelText('Priority'), 'LOW');
    await userEvent.click(form.getByRole('button', { name: 'Save classification' }));

    await waitFor(async () => expect(await entries()).toHaveLength(3));
    expect((await entries())[2]).toContain('Priority: Low (was High)');
  });

  it('keeps the rest of the detail when the history fails, with a retry in the timeline (#170 T6)', async () => {
    let failing = true;
    const state = server([REPORTED], [], {
      history: () => (failing ? problem(500) : json(200, [REPORTED])),
    });
    renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(state) });

    const history = await timeline();
    const alert = await history.findByRole('alert');
    expect(alert.textContent).toContain('The history could not be loaded.');
    expect(screen.getByRole('region', { name: 'Report' })).toBeDefined();
    expect(screen.getByText(INCIDENT.description)).toBeDefined();

    failing = false;
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    await waitFor(async () => expect(await entries()).toHaveLength(1));
  });
});
