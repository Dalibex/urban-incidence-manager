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

const MESSAGE = 'Which street is it on? The position on the map is in the middle of a park.';

const VALIDATED: Incident = { ...REPORTED, status: 'VALIDATED', version: 3 };
const REJECTED: Incident = { ...REPORTED, status: 'REJECTED', version: 3 };

const REASON = 'This streetlight belongs to a private estate; please contact its owners.';

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
    [`POST /incidents/${REPORTED.id}/rejection`]: action('rejection', REJECTED),
    [`POST /incidents/${REPORTED.id}/information-requests`]: action('information-requests', {
      ...REPORTED,
      version: REPORTED.version! + 1,
    }),
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

    const note = validate.getByText(
      'Set its category and priority in Classification and save them first.',
    );
    const button = validate.getByRole('button', { name: 'Validate the incident' });
    // Unavailable but still reached with Tab, and it reads out why.
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button).toHaveProperty('disabled', false);
    expect(button.getAttribute('aria-describedby')).toBe(note.id);
    for (let presses = 0; presses < 40 && document.activeElement !== button; presses++) {
      await userEvent.tab();
    }
    expect(document.activeElement).toBe(button);
    await userEvent.click(button);
    await userEvent.keyboard('{Enter}');
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

describe('ask the citizen for more information (#189)', () => {
  it('asks for the message before sending, then sends it and empties the form', async () => {
    const server = newServer();
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const request = await form('Request information');

    await userEvent.click(request.getByRole('button', { name: 'Send the request' }));
    expect(await request.findByText('Write what you need to know.')).toBeDefined();
    expect(server.sent['information-requests']).toEqual([]);

    await userEvent.type(request.getByLabelText('Message to the citizen'), MESSAGE);
    await userEvent.click(request.getByRole('button', { name: 'Send the request' }));

    expect(
      await screen.findByText('Request sent: the citizen sees it in the history.'),
    ).toBeDefined();
    expect(server.sent['information-requests']).toEqual([
      { version: REPORTED.version, reason: MESSAGE },
    ]);
    // It stays reported, so the actions stay, and a second request starts empty.
    expect(request.getByLabelText<HTMLTextAreaElement>('Message to the citizen').value).toBe('');
    expect(screen.getByRole('form', { name: 'Validate' })).toBeDefined();
  });

  it('sends the next request with the new version', async () => {
    const server = newServer();
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const request = await form('Request information');

    await userEvent.type(request.getByLabelText('Message to the citizen'), MESSAGE);
    await userEvent.click(request.getByRole('button', { name: 'Send the request' }));
    await screen.findByText('Request sent: the citizen sees it in the history.');
    await userEvent.type(request.getByLabelText('Message to the citizen'), 'And which lamp post?');
    await userEvent.click(request.getByRole('button', { name: 'Send the request' }));

    await waitFor(() => expect(server.sent['information-requests']).toHaveLength(2));
    expect(server.sent['information-requests'][1]).toEqual({
      version: REPORTED.version! + 1,
      reason: 'And which lamp post?',
    });
  });

  // T14: the message stays after a 409, a network failure or a 5xx.
  it('keeps the message after a 409, a network failure or a 5xx', async () => {
    const server = newServer();
    const answers: Answer[] = [
      CONFLICT,
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
    ];
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(server, { 'information-requests': () => answers.shift()!() }),
    });
    const request = await form('Request information');
    const send = request.getByRole('button', { name: 'Send the request' });

    await userEvent.type(request.getByLabelText('Message to the citizen'), MESSAGE);
    await userEvent.click(send);
    expect(await request.findByText(/The incident changed since you opened it/)).toBeDefined();
    await userEvent.click(send);
    expect((await request.findByRole('alert')).textContent).toContain(
      'The server cannot be reached',
    );
    await userEvent.click(send);
    expect((await request.findByRole('alert')).textContent).toContain('could not be saved');

    expect(request.getByLabelText('Message to the citizen')).toHaveProperty('value', MESSAGE);
    expect(screen.queryByText('Request sent: the citizen sees it in the history.')).toBeNull();
    expect(server.sent['information-requests']).toHaveLength(3);
  });
});

describe('reject a reported incident (#189)', () => {
  it('rejects an incident without classification, with the reason, and then offers nothing more', async () => {
    const server = newServer(UNCLASSIFIED);
    renderApp(incidentPath(REPORTED.id), { role: 'MUNICIPAL_OPERATOR', fetch: api(server) });
    const reject = await form('Reject');

    await userEvent.click(reject.getByRole('button', { name: 'Reject the incident' }));
    expect(await reject.findByText('Write why it is rejected.')).toBeDefined();
    expect(server.sent.rejection).toEqual([]);

    await userEvent.type(reject.getByLabelText('Reason for the citizen'), REASON);
    await userEvent.click(reject.getByRole('button', { name: 'Reject the incident' }));

    expect(await screen.findByText('Incident rejected.')).toBeDefined();
    expect(await screen.findByText('Only comments are available for this incident.')).toBeDefined();
    expect(server.sent.rejection).toEqual([{ version: REPORTED.version, reason: REASON }]);
    await waitFor(() => expect(document.activeElement?.textContent).toBe('Incident rejected.'));
  });

  // T14: someone else validated it meanwhile; the reason stays.
  it('says when the incident no longer allows rejecting it, and keeps the reason', async () => {
    renderApp(incidentPath(REPORTED.id), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(newServer(), {
        rejection: () => json(409, { status: 409, code: 'INVALID_TRANSITION', title: 'Conflict' }),
      }),
    });
    const reject = await form('Reject');

    await userEvent.type(reject.getByLabelText('Reason for the citizen'), REASON);
    await userEvent.click(reject.getByRole('button', { name: 'Reject the incident' }));

    expect(
      await reject.findByText(
        'This incident no longer allows this action. Reload it to see its status.',
      ),
    ).toBeDefined();
    expect(reject.getByLabelText('Reason for the citizen')).toHaveProperty('value', REASON);
    expect(screen.queryByText('Incident rejected.')).toBeNull();
  });
});
