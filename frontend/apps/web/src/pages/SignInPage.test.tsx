import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

const TOKENS = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const OPERATOR = {
  id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
  name: 'Lucía Martín',
  email: 'lucia.martin@example.com',
  role: 'MUNICIPAL_OPERATOR',
};

async function submit(email = 'lucia.martin@example.com', password = 'secret-password') {
  await userEvent.type(await screen.findByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

function problem(status: number, code?: string) {
  return () => json(status, { type: 'about:blank', title: 'Error', status, code });
}

describe('SignInPage', () => {
  it('signs in and lands the user on the screen of their role', async () => {
    const fetch = fakeApi({
      'POST /sessions': () => json(201, TOKENS),
      'GET /users/me': (request) =>
        request.headers.get('Authorization') === 'Bearer access-1'
          ? json(200, OPERATOR)
          : json(401, { status: 401 }),
    });
    const router = renderApp(PATHS.signIn, { fetch });

    await submit();

    expect(await screen.findByRole('heading', { name: 'Inbox' })).toBeDefined();
    expect(router.state.location.pathname).toBe(PATHS.inbox);
    expect(screen.getByText('Lucía Martín')).toBeDefined();
  });

  it.each([
    [problem(401, 'INVALID_CREDENTIALS'), 'The email or the password is not correct.'],
    [
      problem(403, 'ACCOUNT_DISABLED'),
      'This account is disabled. Ask an administrator to enable it.',
    ],
    [problem(429), 'Too many attempts. Wait a few minutes and try again.'],
    [
      () => {
        throw new TypeError('Failed to fetch');
      },
      'UrbanPulse cannot be reached. Check your connection and try again.',
    ],
  ])('explains a failed sign-in (%#)', async (answer, message) => {
    const router = renderApp(PATHS.signIn, { fetch: fakeApi({ 'POST /sessions': answer }) });

    await submit();

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(router.state.location.pathname).toBe(PATHS.signIn);
    expect(screen.getByRole('button', { name: 'Sign in' })).toHaveProperty('disabled', false);
  });

  it('recovers from an unexpected answer and marks the fields as invalid', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    // A misconfigured proxy answering the app's HTML instead of JSON.
    const fetch = fakeApi({
      'POST /sessions': () => json(201, TOKENS),
      'GET /users/me': () =>
        new Response('<!doctype html>', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      'DELETE /sessions/current': () => new Response(null, { status: 204 }),
    });
    renderApp(PATHS.signIn, { fetch });

    await submit();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Sign-in failed. Try again in a moment.',
    );
    expect(screen.getByRole('button', { name: 'Sign in' })).toHaveProperty('disabled', false);
    expect(screen.getByLabelText('Email').getAttribute('aria-invalid')).toBe('true');
    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it('sends one request even when the button is pressed twice', async () => {
    let release: () => void = () => undefined;
    const fetch = fakeApi({
      'POST /sessions': () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(problem(401, 'INVALID_CREDENTIALS')());
        }),
    });
    renderApp(PATHS.signIn, { fetch });

    await submit();
    await userEvent.click(screen.getByRole('button', { name: 'Signing in…' }));
    release();

    expect(await screen.findByText('The email or the password is not correct.')).toBeDefined();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
