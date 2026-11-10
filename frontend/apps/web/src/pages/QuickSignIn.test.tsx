import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Acceptance tests of #168 for the quick sign-in of the sign-in page (#167). vite.config.ts turns
// the flag off for every test; these turn it on with `vi.stubEnv`.

const DEMO_PASSWORD = 'demo-password-for-the-test';
const BUTTONS = ['Operator', 'Technician', 'Administrator', 'Analyst'];

const TOKENS = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

afterEach(() => {
  vi.unstubAllEnvs();
});

// No default value: `undefined` is a case of its own (T5), the variable absent.
function quickSignIn(password: string | undefined) {
  vi.stubEnv('VITE_QUICK_SIGN_IN', 'true');
  vi.stubEnv('VITE_DEMO_PASSWORD', password);
}

function problem(status: number, code?: string) {
  return () => json(status, { type: 'about:blank', title: 'Error', status, code });
}

function signedIn(role: string, email: string) {
  return {
    'POST /sessions': () => json(201, TOKENS),
    'GET /users/me': () => json(200, { id: 'user-1', name: 'Demo User', email, role }),
  };
}

function signInRequests(fetch: ReturnType<typeof fakeApi>) {
  return fetch.mock.calls.filter(
    ([request]) => new URL(request.url).pathname === '/api/v1/sessions',
  );
}

function quickButton(name: string) {
  return screen.getByRole('button', { name });
}

describe('QuickSignIn', () => {
  // T3
  it('shows nothing with the flag off', async () => {
    vi.stubEnv('VITE_DEMO_PASSWORD', DEMO_PASSWORD);
    renderApp(PATHS.signIn);

    await screen.findByRole('heading', { name: 'Sign in to UrbanPulse' });

    expect(screen.queryByRole('heading', { name: 'Quick sign-in (demo)' })).toBeNull();
    for (const name of BUTTONS) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
  });

  // T3
  it('shows one button per staff role with the flag on', async () => {
    quickSignIn(DEMO_PASSWORD);
    renderApp(PATHS.signIn);

    expect(await screen.findByRole('heading', { name: 'Quick sign-in (demo)' })).toBeDefined();
    for (const name of BUTTONS) {
      expect(quickButton(name)).toHaveProperty('disabled', false);
    }
  });

  // T3, and T1 without a backend: each button signs in as its demo user and opens its screen.
  it.each([
    ['Operator', 'demo.operator@example.com', 'MUNICIPAL_OPERATOR', PATHS.inbox],
    ['Technician', 'demo.technician@example.com', 'TECHNICIAN', PATHS.workQueue],
    ['Administrator', 'demo.admin@example.com', 'ADMINISTRATOR', PATHS.users],
    ['Analyst', 'demo.analyst@example.com', 'ANALYST', PATHS.reports],
  ])('signs in as the demo %s', async (button, email, role, path) => {
    quickSignIn(DEMO_PASSWORD);
    const bodies: unknown[] = [];
    const answers = signedIn(role, email);
    const fetch = fakeApi({
      ...answers,
      'POST /sessions': async (request) => {
        bodies.push(await request.json());
        return answers['POST /sessions']();
      },
    });
    const router = renderApp(PATHS.signIn, { fetch });

    await userEvent.click(await screen.findByRole('button', { name: button }));

    await vi.waitFor(() => expect(router.state.location.pathname).toBe(path));
    expect(bodies).toEqual([{ email, password: DEMO_PASSWORD }]);
  });

  // T4
  it('shows the normal error with a wrong password, without marking the form', async () => {
    quickSignIn('not-the-demo-password');
    const fetch = fakeApi({ 'POST /sessions': problem(401, 'INVALID_CREDENTIALS') });
    const router = renderApp(PATHS.signIn, { fetch });

    await userEvent.click(await screen.findByRole('button', { name: 'Operator' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The email or the password is not correct.',
    );
    expect(router.state.location.pathname).toBe(PATHS.signIn);
    expect(screen.getByLabelText('Email').getAttribute('aria-invalid')).toBeNull();
    expect(screen.getByLabelText('Password').getAttribute('aria-invalid')).toBeNull();
    for (const name of BUTTONS) {
      expect(quickButton(name)).toHaveProperty('disabled', false);
    }
  });

  // T5
  it.each([
    ['empty', ''],
    ['absent', undefined],
  ])('says what is missing when the password is %s, and sends nothing', async (_, password) => {
    quickSignIn(password);
    const fetch = fakeApi({});
    renderApp(PATHS.signIn, { fetch });

    expect(await screen.findByRole('heading', { name: 'Quick sign-in (demo)' })).toBeDefined();
    expect(screen.getByText('VITE_DEMO_PASSWORD')).toBeDefined();
    for (const name of BUTTONS) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
    expect(fetch).not.toHaveBeenCalled();
  });

  // T7
  it.each([
    [
      'no network',
      () => {
        throw new TypeError('Failed to fetch');
      },
      'UrbanPulse cannot be reached. Check your connection and try again.',
    ],
    ['a 502 from the proxy', problem(502), 'Sign-in failed. Try again in a moment.'],
    ['a 500', problem(500), 'Sign-in failed. Try again in a moment.'],
  ])('shows the normal error with %s and lets the user retry', async (_, failure, message) => {
    quickSignIn(DEMO_PASSWORD);
    const answers = signedIn('ADMINISTRATOR', 'demo.admin@example.com');
    const sessions = [failure, answers['POST /sessions']];
    const fetch = fakeApi({ ...answers, 'POST /sessions': () => sessions.shift()!() });
    const router = renderApp(PATHS.signIn, { fetch });

    await userEvent.click(await screen.findByRole('button', { name: 'Administrator' }));

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(quickButton('Administrator')).toHaveProperty('disabled', false);

    await userEvent.click(quickButton('Administrator'));

    await vi.waitFor(() => expect(router.state.location.pathname).toBe(PATHS.users));
  });

  // T8
  it('sends one request on a double click', async () => {
    quickSignIn(DEMO_PASSWORD);
    let release: () => void = () => undefined;
    const fetch = fakeApi({
      'POST /sessions': () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(problem(401, 'INVALID_CREDENTIALS')());
        }),
    });
    renderApp(PATHS.signIn, { fetch });

    const technician = await screen.findByRole('button', { name: 'Technician' });
    await userEvent.dblClick(technician);

    expect(technician).toHaveProperty('disabled', true);
    expect(quickButton('Operator')).toHaveProperty('disabled', true);
    expect(screen.getByRole('button', { name: 'Signing in…' })).toHaveProperty('disabled', true);
    release();

    expect(await screen.findByText('The email or the password is not correct.')).toBeDefined();
    expect(signInRequests(fetch)).toHaveLength(1);
  });

  // T8: the form and the buttons share one guard, so a click on another button does not send a
  // second request either.
  it('sends one request when another button is pressed meanwhile', async () => {
    quickSignIn(DEMO_PASSWORD);
    let release: () => void = () => undefined;
    const fetch = fakeApi({
      'POST /sessions': () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(problem(401, 'INVALID_CREDENTIALS')());
        }),
    });
    renderApp(PATHS.signIn, { fetch });

    await userEvent.click(await screen.findByRole('button', { name: 'Operator' }));
    await userEvent.click(quickButton('Analyst'));
    release();

    expect(await screen.findByText('The email or the password is not correct.')).toBeDefined();
    expect(signInRequests(fetch)).toHaveLength(1);
  });
});
