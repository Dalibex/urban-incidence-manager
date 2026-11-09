import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type Incident = components['schemas']['IncidentResponse'];
type Role = components['schemas']['Role'];

/**
 * The comment form of the staff detail (#50), with T10 of #73: a double click sends one comment,
 * and after no network or a `5xx` the typed text stays to send again. The comments themselves show
 * in the timeline of #169.
 */

const TEXT = 'The access is through the back street.';

const INCIDENT: Incident = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'IN_PROGRESS',
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
  version: 5,
};

const CREATED: components['schemas']['CommentResponse'] = {
  id: 'f6a7b8c9-d0e1-4f2a-b3c4-d5e6f7a8b9c0',
  author: { id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', name: 'Lucía Martín', role: 'TECHNICIAN' },
  text: TEXT,
  createdAt: '2026-11-03T09:00:00Z',
};

function api(
  incident: Incident,
  sent: unknown[],
  answer: () => Response | Promise<Response> = () => json(201, CREATED),
) {
  return fakeApi({
    'GET /categories': () => json(200, []),
    [`GET /incidents/${incident.id}`]: () => json(200, incident),
    [`GET /incidents/${incident.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${incident.id}/attachments`]: () => json(200, []),
    [`POST /incidents/${incident.id}/comments`]: async (request) => {
      sent.push(await request.json());
      return answer();
    },
  });
}

async function commentForm(
  role: Role,
  incident: Incident,
  sent: unknown[],
  answer?: () => Response | Promise<Response>,
) {
  renderApp(incidentPath(incident.id), { role, fetch: api(incident, sent, answer) });
  return within(await screen.findByRole('form', { name: 'Comment' }));
}

describe('comment form of the staff detail (#50)', () => {
  it.each(['MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR'] as const)(
    'lets %s add a comment, then clears the text',
    async (role) => {
      const sent: unknown[] = [];
      const form = await commentForm(role, INCIDENT, sent);

      await userEvent.type(form.getByLabelText('Comment text'), TEXT);
      await userEvent.click(form.getByRole('button', { name: 'Add comment' }));

      expect(await form.findByText('Comment added.')).toBeDefined();
      expect(form.getByLabelText<HTMLTextAreaElement>('Comment text').value).toBe('');
      expect(sent).toEqual([{ text: TEXT }]);
    },
  );

  it('is offered also where no other action is, such as a closed incident', async () => {
    const closed: Incident = { ...INCIDENT, status: 'CLOSED', closedAt: '2026-11-04T10:00:00Z' };
    await commentForm('MUNICIPAL_OPERATOR', closed, []);

    expect(screen.getByText('Only comments are available for this incident.')).toBeDefined();
  });

  it('asks for the text before sending anything', async () => {
    const sent: unknown[] = [];
    const form = await commentForm('TECHNICIAN', INCIDENT, sent);

    await userEvent.type(form.getByLabelText('Comment text'), '   ');
    await userEvent.click(form.getByRole('button', { name: 'Add comment' }));

    expect(await form.findByText('Write the comment.')).toBeDefined();
    expect(sent).toEqual([]);
  });

  it('shows the message of a 422 under the text', async () => {
    const message = 'The comment can have at most 2000 characters.';
    const form = await commentForm('TECHNICIAN', INCIDENT, [], () =>
      json(422, {
        status: 422,
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'text', message }],
      }),
    );

    await userEvent.type(form.getByLabelText('Comment text'), TEXT);
    await userEvent.click(form.getByRole('button', { name: 'Add comment' }));

    expect(await form.findByText(message)).toBeDefined();
    expect(form.getByLabelText('Comment text').getAttribute('aria-invalid')).toBe('true');
  });

  it('offers to reload when the incident is no longer theirs (404)', async () => {
    const form = await commentForm('TECHNICIAN', INCIDENT, [], () =>
      json(404, { status: 404, code: 'NOT_FOUND', title: 'Not Found' }),
    );

    await userEvent.type(form.getByLabelText('Comment text'), TEXT);
    await userEvent.click(form.getByRole('button', { name: 'Add comment' }));

    expect(
      await form.findByText('You can no longer see this incident. Reload it to check.'),
    ).toBeDefined();
    expect(form.getByRole('button', { name: 'Reload the incident' })).toBeDefined();
    expect(form.getByLabelText('Comment text')).toHaveProperty('value', TEXT);
  });

  // T10: a double click sends one comment, and nothing says it was added before the answer.
  it('sends one comment on a double click', async () => {
    const sent: unknown[] = [];
    let respond: (response: Response) => void = () => {};
    const form = await commentForm(
      'TECHNICIAN',
      INCIDENT,
      sent,
      () => new Promise<Response>((resolve) => (respond = resolve)),
    );

    await userEvent.type(form.getByLabelText('Comment text'), TEXT);
    await userEvent.dblClick(form.getByRole('button', { name: 'Add comment' }));
    expect(await form.findByRole('button', { name: 'Saving…' })).toHaveProperty('disabled', true);
    expect(form.queryByText('Comment added.')).toBeNull();

    respond(json(201, CREATED));
    expect(await form.findByText('Comment added.')).toBeDefined();
    expect(sent).toEqual([{ text: TEXT }]);
  });

  // T10: no network, then a 5xx; the text stays and the same comment can be sent again.
  it('keeps the text after a network failure or a 5xx, to send it again', async () => {
    const sent: unknown[] = [];
    const answers: (() => Response)[] = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(500, { status: 500 }),
      () => json(201, CREATED),
    ];
    const form = await commentForm('TECHNICIAN', INCIDENT, sent, () => answers.shift()!());
    const add = form.getByRole('button', { name: 'Add comment' });

    await userEvent.type(form.getByLabelText('Comment text'), TEXT);
    await userEvent.click(add);
    expect((await form.findByRole('alert')).textContent).toContain('The server cannot be reached');
    expect(form.getByLabelText('Comment text')).toHaveProperty('value', TEXT);

    await userEvent.click(add);
    expect((await form.findByRole('alert')).textContent).toContain('could not be saved');
    expect(form.getByLabelText('Comment text')).toHaveProperty('value', TEXT);

    await userEvent.click(add);
    expect(await form.findByText('Comment added.')).toBeDefined();
    await waitFor(() => expect(sent).toEqual([{ text: TEXT }, { text: TEXT }, { text: TEXT }]));
  });
});
