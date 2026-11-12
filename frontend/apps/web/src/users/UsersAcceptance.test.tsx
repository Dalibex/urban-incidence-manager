import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp, userWith } from '../test-utils';

// Acceptance tests of #63 for the Users screen of #191: T9, T18, T19 and T20. The API side of the
// cases is in UserAdministrationApiIT.

type Answer = (request: Request) => Response | Promise<Response>;

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
  role: 'ANALYST',
  enabled: false,
};

const USERS = [
  { id: ME.id, name: ME.name, email: ME.email, role: 'ADMINISTRATOR', enabled: true },
  PEDRO,
  ROSA,
];

function page(content: unknown[]) {
  return json(200, {
    content,
    page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 },
  });
}

const SERVER_ERROR: Answer = () => json(500, { status: 500 });

const NO_NETWORK: Answer = () => {
  throw new TypeError('Failed to fetch');
};

/** Answers each request with the next answer, and with the last one from then on. */
function inTurn(...answers: Answer[]): Answer {
  return (request) => (answers.length > 1 ? answers.shift()! : answers[0]!)(request);
}

function usersApi(extra: Record<string, Answer> = {}, list: Answer = () => page(USERS)) {
  const queries: URLSearchParams[] = [];
  const fetch = fakeApi({
    'GET /users': (request) => {
      queries.push(new URL(request.url).searchParams);
      return list(request);
    },
    ...extra,
  });
  return { fetch, queries };
}

function posts(fetch: ReturnType<typeof fakeApi>, path: string) {
  return fetch.mock.calls.filter(
    ([request]) => request.method === 'POST' && new URL(request.url).pathname === `/api/v1${path}`,
  );
}

async function fillCreateForm() {
  await userEvent.click(await screen.findByRole('button', { name: 'New staff account' }));
  await userEvent.type(screen.getByLabelText('Name'), 'Ana Torres');
  await userEvent.type(screen.getByLabelText('Email'), 'ana.torres@example.com');
  await userEvent.type(screen.getByLabelText('Initial password'), 'correct-horse');
  await userEvent.selectOptions(screen.getByLabelText('Role'), 'Analyst');
}

function expectTheFormKept() {
  expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Ana Torres');
  expect((screen.getByLabelText('Email') as HTMLInputElement).value).toBe('ana.torres@example.com');
  expect((screen.getByLabelText('Initial password') as HTMLInputElement).value).toBe(
    'correct-horse',
  );
  expect((screen.getByLabelText('Role') as HTMLSelectElement).value).toBe('ANALYST');
}

describe('users screen, acceptance of #63', () => {
  // T20
  it('takes an administrator to the Users screen after signing in', async () => {
    const { fetch } = usersApi({
      'POST /sessions': () =>
        json(201, {
          tokenType: 'Bearer',
          accessToken: 'access-1',
          accessTokenExpiresAt: '2026-11-10T10:15:00Z',
          refreshToken: 'refresh-1',
          refreshTokenExpiresAt: '2026-11-24T10:00:00Z',
        }),
      'GET /users/me': () => json(200, { ...USERS[0] }),
    });
    const router = renderApp(PATHS.signIn, { fetch });

    await userEvent.type(await screen.findByLabelText('Email'), ME.email);
    await userEvent.type(screen.getByLabelText('Password'), 'any-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(PATHS.users));
    expect(await screen.findByRole('table')).toBeDefined();
  });

  // T18: the list fails with a 5xx, then loads on Try again.
  it('explains a server failure of the list and loads it on retry', async () => {
    const { fetch } = usersApi(
      {},
      inTurn(SERVER_ERROR, SERVER_ERROR, SERVER_ERROR, () => page(USERS)),
    );
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The users could not be loaded. Try again in a moment.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('table')).toBeDefined();
  });

  // T18: without network the list says so, and the filters stay.
  it('says the server cannot be reached when the list fails without network', async () => {
    const { fetch } = usersApi({}, NO_NETWORK);
    renderApp(`${PATHS.users}?role=TECHNICIAN`, { role: 'ADMINISTRATOR', fetch });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(screen.getByRole<HTMLInputElement>('checkbox', { name: 'Technician' }).checked).toBe(
      true,
    );
  });

  // T18: creating without network keeps every value and says nothing about a success; Try again
  // creates one account.
  it('keeps the form when creating fails without network, and creates once on retry', async () => {
    const { fetch } = usersApi({
      'POST /users': inTurn(NO_NETWORK, () =>
        json(201, { ...PEDRO, id: '33333333-3333-4333-8333-333333333333' }),
      ),
    });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });
    await fillCreateForm();

    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(screen.queryByText(/Account created/)).toBeNull();
    expectTheFormKept();

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText(/Account created/)).toBeDefined();
    // One failed attempt and one that created the account.
    expect(posts(fetch, '/users')).toHaveLength(2);
  });

  // T18: a 5xx while editing keeps what was typed and says nothing about a success.
  it('keeps the edited name when saving fails with a 5xx', async () => {
    const { fetch } = usersApi({ [`PATCH /users/${PEDRO.id}`]: SERVER_ERROR });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await userEvent.click(await screen.findByRole('button', { name: 'Edit Pedro Gómez' }));
    const name = screen.getByLabelText('Name');
    await userEvent.clear(name);
    await userEvent.type(name, 'Pedro G.');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The changes could not be saved. Try again in a moment.',
    );
    expect(screen.queryByText('Changes saved.')).toBeNull();
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Pedro G.');
  });

  // T19: a double click on Create sends one account.
  it('creates one account on a double click', async () => {
    let answer: (response: Response) => void = () => undefined;
    const { fetch } = usersApi({
      'POST /users': () =>
        new Promise<Response>((resolve) => {
          answer = resolve;
        }),
    });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });
    await fillCreateForm();

    await userEvent.dblClick(screen.getByRole('button', { name: 'Create account' }));

    const creating = await screen.findByRole('button', { name: 'Creating…' });
    expect((creating as HTMLButtonElement).disabled).toBe(true);
    // No success before the answer.
    expect(screen.queryByText(/Account created/)).toBeNull();
    answer(json(201, { ...PEDRO, id: '33333333-3333-4333-8333-333333333333' }));

    expect(await screen.findByText(/Account created/)).toBeDefined();
    expect(posts(fetch, '/users')).toHaveLength(1);
  });

  // T9: an email already used shows under the Email field, with what was typed kept.
  it('shows an email already used under the Email field', async () => {
    const { fetch } = usersApi({
      'POST /users': () =>
        json(409, { status: 409, code: 'EMAIL_ALREADY_EXISTS', title: 'Conflict' }),
    });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });
    await fillCreateForm();

    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    const message = await screen.findByText('An account with this email already exists.');
    expect(screen.getByLabelText('Email').getAttribute('aria-describedby')).toContain(message.id);
    expect(screen.getByLabelText('Email').getAttribute('aria-invalid')).toBe('true');
    expect(screen.queryByText(/Account created/)).toBeNull();
    expectTheFormKept();
  });

  // T9: the text filters reach the API and the address.
  it('filters by name and email from the bar and keeps them in the address', async () => {
    const { fetch, queries } = usersApi();
    const router = renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await userEvent.type(await screen.findByLabelText('Name contains'), 'Díaz');
    await userEvent.type(screen.getByLabelText('Email contains'), 'example');

    await waitFor(() => {
      expect(queries.at(-1)!.get('name')).toBe('Díaz');
      expect(queries.at(-1)!.get('email')).toBe('example');
    });
    const address = new URLSearchParams(router.state.location.search);
    expect(address.get('name')).toBe('Díaz');
    expect(address.get('email')).toBe('example');
  });

  // #270: both pauses end before the screen renders again, as on a loaded machine. Fake timers make
  // it happen every time: both pauses end in the same `act`.
  it('applies the name and the email when both pauses end together', async () => {
    const { fetch, queries } = usersApi();
    const router = renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });
    const name = await screen.findByLabelText<HTMLInputElement>('Name contains');

    vi.useFakeTimers();
    try {
      fireEvent.change(name, { target: { value: 'Díaz' } });
      fireEvent.change(screen.getByLabelText('Email contains'), { target: { value: 'example' } });
      // Longer than the pause of 300 ms of each text.
      act(() => {
        vi.advanceTimersByTime(400);
      });
    } finally {
      vi.useRealTimers();
    }

    const address = new URLSearchParams(router.state.location.search);
    expect(address.get('name')).toBe('Díaz');
    expect(address.get('email')).toBe('example');
    expect(name.value).toBe('Díaz');
    await waitFor(() => {
      expect(queries.at(-1)!.get('name')).toBe('Díaz');
      expect(queries.at(-1)!.get('email')).toBe('example');
    });
  });

  // #270: a role applies at once, and a text pause still pending then applies on top of it.
  it('keeps a role chosen while the pause of the name is pending', async () => {
    const { fetch } = usersApi();
    const router = renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });
    const name = await screen.findByLabelText('Name contains');

    vi.useFakeTimers();
    try {
      fireEvent.change(name, { target: { value: 'Díaz' } });
      fireEvent.click(screen.getByRole('checkbox', { name: 'Analyst' }));
      act(() => {
        vi.advanceTimersByTime(400);
      });
    } finally {
      vi.useRealTimers();
    }

    const address = new URLSearchParams(router.state.location.search);
    expect(address.get('name')).toBe('Díaz');
    expect(address.getAll('role')).toEqual(['ANALYST']);
  });

  // T9: a deactivated account is reactivated, and only `enabled` is sent.
  it('reactivates a deactivated account', async () => {
    const bodies: unknown[] = [];
    const { fetch } = usersApi({
      [`PATCH /users/${ROSA.id}`]: async (request) => {
        bodies.push(await request.json());
        return json(200, { ...ROSA, enabled: true });
      },
    });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await userEvent.click(await screen.findByRole('button', { name: 'Edit Rosa Díaz' }));
    await userEvent.click(screen.getByRole('button', { name: 'Reactivate account' }));

    expect(await screen.findByText('Account reactivated.')).toBeDefined();
    expect(bodies).toEqual([{ enabled: true }]);
  });

  // T9: deactivating asks first; cancelling sends nothing.
  it('asks before deactivating, and sends nothing when cancelled', async () => {
    const { fetch } = usersApi({ [`PATCH /users/${PEDRO.id}`]: () => json(200, PEDRO) });
    renderApp(PATHS.users, { role: 'ADMINISTRATOR', fetch });

    await userEvent.click(await screen.findByRole('button', { name: 'Edit Pedro Gómez' }));
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate account' }));
    const confirm = screen.getByRole('group', { name: 'Confirm the deactivation' });
    await userEvent.click(within(confirm).getByRole('button', { name: /cancel|keep|no/i }));

    expect(screen.queryByRole('group', { name: 'Confirm the deactivation' })).toBeNull();
    expect(fetch.mock.calls.filter(([request]) => request.method === 'PATCH')).toHaveLength(0);
  });
});
