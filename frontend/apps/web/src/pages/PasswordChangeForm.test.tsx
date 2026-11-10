import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Acceptance tests of #166 for the step of the sign-in page that #165 adds: an account that must
// change its password chooses a new one before its first session.

const EMAIL = 'rosa.medina@example.com';
const GIVEN_PASSWORD = 'given-by-the-admin';
const NEW_PASSWORD = 'only-i-know-this';

const PASSWORD_CHANGE_REQUIRED = () =>
  json(403, {
    type: 'about:blank',
    title: 'Forbidden',
    status: 403,
    code: 'PASSWORD_CHANGE_REQUIRED',
  });

function problem(status: number, code?: string) {
  return () => json(status, { type: 'about:blank', title: 'Error', status, code });
}

function noContent() {
  return new Response(null, { status: 204 });
}

/** Signs in with the given password, which the API answers with the change step. */
async function reachThePasswordChange() {
  await userEvent.type(await screen.findByLabelText('Email'), EMAIL);
  await userEvent.type(screen.getByLabelText('Password'), GIVEN_PASSWORD);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByRole('heading', { name: 'Choose your password' });
}

async function typeNewPassword(newPassword = NEW_PASSWORD, repeated = newPassword) {
  await userEvent.type(screen.getByLabelText('New password'), newPassword);
  await userEvent.type(screen.getByLabelText('Repeat the new password'), repeated);
}

function changeButton() {
  return screen.getByRole('button', { name: 'Change the password' });
}

function expectEmptyPasswordFields() {
  expect(screen.getByLabelText('New password')).toHaveProperty('value', '');
  expect(screen.getByLabelText('Repeat the new password')).toHaveProperty('value', '');
}

describe('PasswordChangeForm', () => {
  it('asks for a new password, sends it with the given one and returns to the sign-in', async () => {
    const bodies: unknown[] = [];
    const fetch = fakeApi({
      'POST /sessions': PASSWORD_CHANGE_REQUIRED,
      'POST /password-changes': async (request) => {
        bodies.push(await request.json());
        return noContent();
      },
    });
    renderApp(PATHS.signIn, { fetch });

    await reachThePasswordChange();
    expect(screen.getByText(new RegExp(EMAIL))).toBeDefined();
    expect(document.activeElement).toBe(screen.getByLabelText('New password'));
    await typeNewPassword();
    await userEvent.click(changeButton());

    expect((await screen.findByRole('status')).textContent).toBe(
      'Your password has changed. Sign in with the new one.',
    );
    expect(bodies).toEqual([
      { email: EMAIL, currentPassword: GIVEN_PASSWORD, newPassword: NEW_PASSWORD },
    ]);
    expect(screen.getByLabelText('Email')).toHaveProperty('value', EMAIL);
    expect(screen.getByLabelText('Password')).toHaveProperty('value', '');
    expect(document.activeElement).toBe(screen.getByLabelText('Password'));
  });

  it('goes back to the sign-in without sending anything', async () => {
    const fetch = fakeApi({ 'POST /sessions': PASSWORD_CHANGE_REQUIRED });
    renderApp(PATHS.signIn, { fetch });

    await reachThePasswordChange();
    await userEvent.click(screen.getByRole('button', { name: 'Back to sign in' }));

    expect(await screen.findByRole('heading', { name: 'Sign in to UrbanPulse' })).toBeDefined();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getByLabelText('Password')).toHaveProperty('value', '');
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['short', 'Short-1', 'Short-1', 'The new password must have at least 8 characters.'],
    [
      'over 72 bytes',
      'ñ'.repeat(40),
      'ñ'.repeat(40),
      'The new password is too long. Use a shorter one.',
    ],
    [
      'the given one',
      GIVEN_PASSWORD,
      GIVEN_PASSWORD,
      'The new password must be different from the one you were given.',
    ],
    ['repeated wrong', NEW_PASSWORD, `${NEW_PASSWORD}-typo`, 'The two new passwords do not match.'],
  ])(
    'refuses a new password that is %s without sending it',
    async (_, newPassword, repeated, message) => {
      const fetch = fakeApi({ 'POST /sessions': PASSWORD_CHANGE_REQUIRED });
      renderApp(PATHS.signIn, { fetch });

      await reachThePasswordChange();
      await typeNewPassword(newPassword, repeated);
      await userEvent.click(changeButton());

      expect((await screen.findByRole('alert')).textContent).toBe(message);
      expectEmptyPasswordFields();
      expect(screen.getByLabelText('New password').getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(screen.getByLabelText('New password'));
      expect(fetch).toHaveBeenCalledTimes(1);
    },
  );

  // T16
  it.each([
    [problem(500), 'The password could not be changed. Try again in a moment.'],
    [problem(503), 'The password could not be changed. Try again in a moment.'],
    [
      () => {
        throw new TypeError('Failed to fetch');
      },
      'UrbanPulse cannot be reached. Check your connection and try again.',
    ],
  ])(
    'explains a server or network failure and lets the user retry (%#)',
    async (answer, message) => {
      const answers = [answer, noContent];
      const fetch = fakeApi({
        'POST /sessions': PASSWORD_CHANGE_REQUIRED,
        'POST /password-changes': () => answers.shift()!(),
      });
      renderApp(PATHS.signIn, { fetch });

      await reachThePasswordChange();
      await typeNewPassword();
      await userEvent.click(changeButton());

      expect((await screen.findByRole('alert')).textContent).toBe(message);
      expect(screen.queryByRole('status')).toBeNull();
      expect(screen.getByRole('heading', { name: 'Choose your password' })).toBeDefined();
      expectEmptyPasswordFields();
      expect(changeButton()).toHaveProperty('disabled', false);

      await typeNewPassword();
      await userEvent.click(changeButton());

      expect((await screen.findByRole('status')).textContent).toBe(
        'Your password has changed. Sign in with the new one.',
      );
    },
  );

  it('recovers from an unexpected answer without a success message', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const fetch = fakeApi({
      'POST /sessions': PASSWORD_CHANGE_REQUIRED,
      'POST /password-changes': () => {
        throw new Error('A bug in the client');
      },
    });
    renderApp(PATHS.signIn, { fetch });

    await reachThePasswordChange();
    await typeNewPassword();
    await userEvent.click(changeButton());

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The password could not be changed. Try again in a moment.',
    );
    expect(screen.queryByRole('status')).toBeNull();
    expectEmptyPasswordFields();
    expect(changeButton()).toHaveProperty('disabled', false);
    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it.each([
    [
      problem(401, 'INVALID_CREDENTIALS'),
      'The password you signed in with is no longer valid. Go back and sign in again.',
    ],
    [
      problem(403, 'ACCOUNT_DISABLED'),
      'This account is disabled. Ask an administrator to enable it.',
    ],
    [
      problem(422, 'VALIDATION_FAILED'),
      'Choose another password: at least 8 characters, different from the one you were given.',
    ],
    [problem(429, 'TOO_MANY_ATTEMPTS'), 'Too many attempts. Wait a few minutes and try again.'],
  ])('explains a refused change (%#)', async (answer, message) => {
    const fetch = fakeApi({
      'POST /sessions': PASSWORD_CHANGE_REQUIRED,
      'POST /password-changes': answer,
    });
    renderApp(PATHS.signIn, { fetch });

    await reachThePasswordChange();
    await typeNewPassword();
    await userEvent.click(changeButton());

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(screen.queryByRole('status')).toBeNull();
    expectEmptyPasswordFields();
  });

  // T17
  it('sends one request even when the button is pressed twice', async () => {
    let release: () => void = () => undefined;
    const fetch = fakeApi({
      'POST /sessions': PASSWORD_CHANGE_REQUIRED,
      'POST /password-changes': () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(noContent());
        }),
    });
    renderApp(PATHS.signIn, { fetch });

    await reachThePasswordChange();
    await typeNewPassword();
    await userEvent.click(changeButton());
    const pending = screen.getByRole('button', { name: 'Changing the password…' });
    expect(pending).toHaveProperty('disabled', true);
    expect(screen.getByRole('button', { name: 'Back to sign in' })).toHaveProperty(
      'disabled',
      true,
    );
    await userEvent.click(pending);
    // No success before the API answers.
    expect(screen.queryByRole('status')).toBeNull();
    release();

    expect(await screen.findByRole('status')).toBeDefined();
    const changes = fetch.mock.calls.filter(
      ([request]) => new URL(request.url).pathname === '/api/v1/password-changes',
    );
    expect(changes).toHaveLength(1);
  });
});
