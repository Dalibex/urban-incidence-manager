import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp, userWith } from '../test-utils';

// The reset of the second factor of #57 on the Users screen: an administrator removes it, with a
// reason for the audit log, when the owner lost the phone (case T2 of #77).

const ME = userWith('ADMINISTRATOR');

const PEDRO = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Pedro Gómez',
  email: 'pedro.gomez@example.com',
  role: 'TECHNICIAN',
  enabled: true,
};

const ROSA = {
  id: '22222222-2222-4222-8222-222222222222',
  name: 'Rosa Díaz',
  email: 'rosa.diaz@example.com',
  role: 'CITIZEN',
  enabled: true,
};

const USERS = [{ ...ME, enabled: true }, PEDRO, ROSA];
const RESET_PATH = `POST /users/${PEDRO.id}/mfa-resets`;

type Answer = Parameters<typeof fakeApi>[0][string];

function usersApi(extra: Record<string, Answer> = {}) {
  return fakeApi({
    'GET /users': () =>
      json(200, {
        content: USERS,
        page: { number: 0, size: 20, totalElements: USERS.length, totalPages: 1 },
      }),
    ...extra,
  });
}

async function openReset() {
  await userEvent.click(await screen.findByRole('button', { name: 'Edit Pedro Gómez' }));
  await userEvent.click(screen.getByRole('button', { name: 'Reset second factor' }));
}

const noContent = () => new Response(null, { status: 204 });

describe('reset of the second factor', () => {
  it('sends the reason after a confirmation and says the second factor is reset', async () => {
    const bodies: unknown[] = [];
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi({
        [RESET_PATH]: async (request) => {
          bodies.push(await request.json());
          return noContent();
        },
      }),
    });

    await openReset();
    // Nothing is sent before the administrator confirms with a reason.
    expect(bodies).toEqual([]);
    await userEvent.type(screen.getByLabelText('Reason'), '  Lost phone ');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

    expect(
      await screen.findByText(
        'Second factor reset. Pedro Gómez sets it up again at the next sign-in.',
      ),
    ).toBeDefined();
    expect(bodies).toEqual([{ reason: 'Lost phone' }]);
    expect(screen.getByRole('button', { name: 'Reset second factor' })).toBeDefined();
  });

  it('does not send a blank reason', async () => {
    const fetch = usersApi();
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await openReset();
    await userEvent.type(screen.getByLabelText('Reason'), '   ');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

    expect(await screen.findByText('Enter the reason, for the audit log.')).toBeDefined();
    expect(screen.getByLabelText('Reason').getAttribute('aria-invalid')).toBe('true');
    // Only the list was requested.
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('cancels without sending anything', async () => {
    const fetch = usersApi();
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await openReset();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByRole('button', { name: 'Reset second factor' })).toBeDefined();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each([
    [() => json(404, { status: 404 }), 'This account no longer exists.'],
    [() => json(403, { status: 403 }), 'You are no longer allowed to manage users.'],
  ])('explains a refusal of the API (%#)', async (answer, message) => {
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch: usersApi({ [RESET_PATH]: answer }) });

    await openReset();
    await userEvent.type(screen.getByLabelText('Reason'), 'Lost phone');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

    expect((await screen.findByRole('alert')).textContent).toContain(message);
  });

  it('keeps the reason and offers to try again when the server fails', async () => {
    const answers = [() => json(500, { status: 500 }), noContent];
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi({ [RESET_PATH]: () => answers.shift()!() }),
    });

    await openReset();
    await userEvent.type(screen.getByLabelText('Reason'), 'Lost phone');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('The second factor could not be reset.');
    expect(screen.getByLabelText('Reason')).toHaveProperty('value', 'Lost phone');
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText(/^Second factor reset\./)).toBeDefined();
    expect(answers).toHaveLength(0);
  });

  it('does not send a reason of more than 500 characters', async () => {
    const fetch = usersApi();
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await openReset();
    // `paste` instead of `type`: 501 keystrokes would be slow and prove nothing more.
    await userEvent.click(screen.getByLabelText('Reason'));
    await userEvent.paste('a'.repeat(501));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

    expect(await screen.findByText('The reason can have at most 500 characters.')).toBeDefined();
    expect(document.activeElement).toBe(screen.getByLabelText('Reason'));
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('shows a 422 of the server under the reason and keeps what was typed', async () => {
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi({
        [RESET_PATH]: () =>
          json(422, {
            status: 422,
            code: 'VALIDATION_FAILED',
            errors: [{ field: 'reason', message: 'The reason must have text.' }],
          }),
      }),
    });

    await openReset();
    await userEvent.type(screen.getByLabelText('Reason'), 'Lost phone');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

    expect(await screen.findByText('The reason must have text.')).toBeDefined();
    const reason = screen.getByLabelText('Reason');
    expect(reason.getAttribute('aria-invalid')).toBe('true');
    expect(reason).toHaveProperty('value', 'Lost phone');
    expect(document.activeElement).toBe(reason);
  });

  it('offers to try again when the server cannot be reached, keeping the reason', async () => {
    const answers = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      noContent,
    ];
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi({ [RESET_PATH]: () => answers.shift()!() }),
    });

    await openReset();
    await userEvent.type(screen.getByLabelText('Reason'), 'Lost phone');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('The server cannot be reached.');
    expect(screen.getByLabelText('Reason')).toHaveProperty('value', 'Lost phone');
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText(/^Second factor reset\./)).toBeDefined();
  });

  it('sends one request when the button is pressed twice', async () => {
    let release: () => void = () => undefined;
    let sent = 0;
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi({
        [RESET_PATH]: () => {
          sent += 1;
          return new Promise<Response>((resolve) => {
            release = () => resolve(noContent());
          });
        },
      }),
    });

    await openReset();
    await userEvent.type(screen.getByLabelText('Reason'), 'Lost phone');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));
    // The button is disabled while the request is on its way: a second press does nothing.
    await userEvent.click(await screen.findByRole('button', { name: 'Resetting…' }));
    release();

    expect(await screen.findByText(/^Second factor reset\./)).toBeDefined();
    expect(sent).toBe(1);
  });

  it('warns that resetting your own account ends your session, then says it is reset', async () => {
    const fetch = usersApi({ [`POST /users/${ME.id}/mfa-resets`]: noContent });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await userEvent.click(await screen.findByRole('button', { name: `Edit ${ME.name}` }));
    await userEvent.click(screen.getByRole('button', { name: 'Reset second factor' }));
    expect(
      screen.getByText(/This is your own account: you will have to sign in again too\./),
    ).toBeDefined();
    await userEvent.type(screen.getByLabelText('Reason'), 'New phone');
    await userEvent.click(screen.getByRole('button', { name: 'Yes, reset' }));

    expect(
      await screen.findByText(
        `Second factor reset. ${ME.name} sets it up again at the next sign-in.`,
      ),
    ).toBeDefined();
  });

  it('is not offered for a citizen account', async () => {
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch: usersApi() });

    await userEvent.click(await screen.findByRole('button', { name: 'Edit Rosa Díaz' }));

    expect(screen.queryByRole('button', { name: 'Reset second factor' })).toBeNull();
  });
});
