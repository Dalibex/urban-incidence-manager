import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp, userWith } from '../test-utils';

const ME = userWith('ADMINISTRATOR');

const USERS = [
  {
    id: ME.id,
    name: ME.name,
    email: ME.email,
    role: 'ADMINISTRATOR',
    enabled: true,
  },
  {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Pedro Gómez',
    email: 'pedro.gomez@example.com',
    role: 'TECHNICIAN',
    enabled: true,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    name: 'Rosa Díaz',
    email: 'rosa.diaz@example.com',
    role: 'CITIZEN',
    enabled: false,
  },
];

function page(content: unknown[]) {
  return json(200, {
    content,
    page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 },
  });
}

function usersApi(requests: URLSearchParams[], extra: Parameters<typeof fakeApi>[0] = {}) {
  return fakeApi({
    'GET /users': (request) => {
      requests.push(new URL(request.url).searchParams);
      return page(USERS);
    },
    ...extra,
  });
}

describe('users screen', () => {
  it('lists the accounts with their role and status in words', async () => {
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch: usersApi([]) });

    const rows = within(await screen.findByRole('table'))
      .getAllByRole('row')
      .slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]!.textContent).toContain('(you)');
    expect(rows[1]!.textContent).toContain('Technician');
    expect(rows[1]!.textContent).toContain('Active');
    expect(rows[2]!.textContent).toContain('Citizen');
    expect(rows[2]!.textContent).toContain('Deactivated');
  });

  it('filters by a role from the bar and keeps it in the address', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.users}?page=2`, {
      role: 'ADMINISTRATOR',
      fetch: usersApi(requests),
    });

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Technician' }));

    await waitFor(() => expect(requests.at(-1)!.getAll('role')).toEqual(['TECHNICIAN']));
    // Another filter gives other pages, so it starts on the first one.
    expect(router.state.location.search).toBe('?role=TECHNICIAN');
  });

  it('creates a staff account and shows the success after the answer', async () => {
    let sent: unknown = undefined;
    const requests: URLSearchParams[] = [];
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi(requests, {
        'POST /users': async (request) => {
          sent = await request.json();
          return json(201, { ...USERS[1], id: '33333333-3333-4333-8333-333333333333' });
        },
      }),
    });

    await userEvent.click(await screen.findByRole('button', { name: 'New staff account' }));
    await userEvent.type(screen.getByLabelText('Name'), 'Ana Torres');
    await userEvent.type(screen.getByLabelText('Email'), 'ana.torres@example.com');
    await userEvent.type(screen.getByLabelText('Initial password'), 'correct-horse');
    await userEvent.selectOptions(screen.getByLabelText('Role'), 'Analyst');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText(/Account created/)).toBeDefined();
    expect(sent).toEqual({
      name: 'Ana Torres',
      email: 'ana.torres@example.com',
      password: 'correct-horse',
      role: 'ANALYST',
    });
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('');
    // The list is read again after the creation.
    await waitFor(() => expect(requests.length).toBeGreaterThan(1));
  });

  it('shows the message of a 422 under its field and keeps what was typed', async () => {
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi([], {
        'POST /users': () =>
          json(422, {
            status: 422,
            code: 'VALIDATION_FAILED',
            errors: [{ field: 'password', message: 'This password is too common.' }],
          }),
      }),
    });

    await userEvent.click(await screen.findByRole('button', { name: 'New staff account' }));
    await userEvent.type(screen.getByLabelText('Name'), 'Ana Torres');
    await userEvent.type(screen.getByLabelText('Email'), 'ana.torres@example.com');
    await userEvent.type(screen.getByLabelText('Initial password'), 'password1');
    await userEvent.selectOptions(screen.getByLabelText('Role'), 'Analyst');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    const message = await screen.findByText('This password is too common.');
    expect(screen.getByLabelText('Initial password').getAttribute('aria-describedby')).toContain(
      message.id,
    );
    expect(screen.queryByText(/Account created/)).toBeNull();
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Ana Torres');
  });

  it('sends only the changed field when an account is edited', async () => {
    let sent: unknown = undefined;
    renderApp(PATHS.users, {
      role: 'ADMINISTRATOR',
      fetch: usersApi([], {
        'PATCH /users/11111111-1111-4111-8111-111111111111': async (request) => {
          sent = await request.json();
          return json(200, { ...USERS[1], name: 'Pedro G.' });
        },
      }),
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Edit Pedro Gómez' }));
    const name = screen.getByLabelText('Name');
    await userEvent.clear(name);
    await userEvent.type(name, 'Pedro G.');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Changes saved.')).toBeDefined();
    expect(sent).toEqual({ name: 'Pedro G.' });
  });

  it('does not offer to change the own role or to deactivate the own account', async () => {
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch: usersApi([]) });

    await userEvent.click(await screen.findByRole('button', { name: `Edit ${ME.name}` }));

    expect((screen.getByLabelText('Role') as HTMLSelectElement).disabled).toBe(true);
    expect(
      (screen.getByRole('button', { name: 'Deactivate account' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect((screen.getByLabelText('Name') as HTMLInputElement).disabled).toBe(false);
  });

  describe('after the first answer', () => {
    const PEDRO = USERS[1]!;
    const PEDRO_PATH = `PATCH /users/${PEDRO.id}`;

    async function fillCreateForm() {
      await userEvent.click(await screen.findByRole('button', { name: 'New staff account' }));
      await userEvent.type(screen.getByLabelText('Name'), 'Ana Torres');
      await userEvent.type(screen.getByLabelText('Email'), 'ana.torres@example.com');
      await userEvent.type(screen.getByLabelText('Initial password'), 'correct-horse');
      await userEvent.selectOptions(screen.getByLabelText('Role'), 'Analyst');
    }

    // Another administrator renamed the account: the answer carries it, and it is not our change.
    it('does not send back a change another administrator made, seen in an answer', async () => {
      const bodies: unknown[] = [];
      renderApp(PATHS.users, {
        role: 'ADMINISTRATOR',
        fetch: usersApi([], {
          [PEDRO_PATH]: async (request) => {
            const body = (await request.json()) as Record<string, unknown>;
            bodies.push(body);
            return json(200, {
              ...PEDRO,
              name: 'Peter',
              ...(body.enabled === false && { enabled: false }),
              ...(typeof body.role === 'string' && { role: body.role }),
            });
          },
        }),
      });

      await userEvent.click(await screen.findByRole('button', { name: 'Edit Pedro Gómez' }));
      await userEvent.click(screen.getByRole('button', { name: 'Deactivate account' }));
      await userEvent.click(screen.getByRole('button', { name: 'Yes, deactivate' }));
      await screen.findByText('Account deactivated.');

      expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Peter');
      expect(
        (screen.getByRole('button', { name: 'Save changes' }) as HTMLButtonElement).disabled,
      ).toBe(true);

      await userEvent.selectOptions(screen.getByLabelText('Role'), 'Analyst');
      await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
      await screen.findByText('Changes saved.');
      expect(bodies).toEqual([{ enabled: false }, { role: 'ANALYST' }]);
    });

    it('keeps the panel and its result when the change takes the row out of the filters', async () => {
      let changed = false;
      renderApp(`${PATHS.users}?role=TECHNICIAN`, {
        role: 'ADMINISTRATOR',
        fetch: fakeApi({
          'GET /users': () => page(changed ? [] : [PEDRO]),
          [PEDRO_PATH]: () => {
            changed = true;
            return json(200, { ...PEDRO, role: 'ANALYST' });
          },
        }),
      });

      await userEvent.click(await screen.findByRole('button', { name: 'Edit Pedro Gómez' }));
      await userEvent.selectOptions(screen.getByLabelText('Role'), 'Analyst');
      await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

      expect(await screen.findByText('Changes saved.')).toBeDefined();
      expect(document.activeElement).not.toBe(document.body);

      await userEvent.click(screen.getByRole('button', { name: 'Close' }));

      expect(await screen.findByText('No users match these filters.')).toBeDefined();
      expect(document.activeElement).not.toBe(document.body);
    });

    it('keeps the focus on Try again while it tries again', async () => {
      let answer: (response: Response) => void = () => undefined;
      let calls = 0;
      renderApp(PATHS.users, {
        role: 'ADMINISTRATOR',
        fetch: usersApi([], {
          'POST /users': () => {
            calls += 1;
            return calls === 1
              ? json(503, { status: 503 })
              : new Promise<Response>((resolve) => {
                  answer = resolve;
                });
          },
        }),
      });
      await fillCreateForm();
      await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

      const retry = await screen.findByRole('button', { name: 'Try again' });
      retry.focus();
      await userEvent.keyboard('{Enter}');

      const trying = await screen.findByRole('button', { name: 'Trying again…' });
      expect((trying as HTMLButtonElement).disabled).toBe(true);
      expect(document.activeElement).not.toBe(document.body);
      // What was typed cannot change while the request goes.
      expect((screen.getByLabelText('Name') as HTMLInputElement).readOnly).toBe(true);

      await act(async () => answer(json(201, PEDRO)));
      expect(await screen.findByText(/Account created/)).toBeDefined();
      expect(document.activeElement).not.toBe(document.body);
    });

    it('offers no retry when the administrator is no longer allowed', async () => {
      renderApp(PATHS.users, {
        role: 'ADMINISTRATOR',
        fetch: usersApi([], { 'POST /users': () => json(403, { status: 403 }) }),
      });
      await fillCreateForm();
      await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

      expect((await screen.findByRole('alert')).textContent).toContain(
        'You are no longer allowed to manage users.',
      );
      expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    });
  });
});
