import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// The step of the sign-in page that asks for the code of the authenticator app (#57, ADR-0021,
// case T15 of #77): staff sign in with the password, then with a code; the first time they set it up.

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

const CHALLENGE = {
  challengeToken: 'challenge-1',
  challengeExpiresAt: '2026-11-21T10:05:00Z',
  enrollmentRequired: false,
};

const ENROLLMENT = {
  otpauthUri: 'otpauth://totp/UrbanPulse:lucia.martin%40example.com?secret=JBSWY3DPEHPK3PXP',
  secret: 'JBSWY3DPEHPK3PXP',
};

function problem(status: number, code?: string) {
  return () => json(status, { type: 'about:blank', title: 'Error', status, code });
}

const challengeAnswer = (enrollmentRequired = false) => ({
  'POST /sessions': () => json(202, { ...CHALLENGE, enrollmentRequired }),
});

async function enterPassword() {
  await userEvent.type(await screen.findByLabelText('Email'), OPERATOR.email);
  await userEvent.type(screen.getByLabelText('Password'), 'secret-password');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

async function enterCode(code = '123456') {
  await userEvent.type(await screen.findByLabelText('Code'), code);
  await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
}

/** The body of every request the fake received for `route`, in order. */
async function bodiesOf(fetch: ReturnType<typeof fakeApi>, path: string) {
  const requests = fetch.mock.calls
    .map(([request]) => request)
    .filter((request) => new URL(request.url).pathname.endsWith(path));
  return Promise.all(requests.map((request) => request.clone().json()));
}

describe('second factor step', () => {
  it('asks for a code after the password, and signs in with it', async () => {
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': () => json(201, TOKENS),
      'GET /users/me': () => json(200, OPERATOR),
    });
    const router = renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    expect(await screen.findByRole('heading', { name: 'Enter your code' })).toBeDefined();
    // No session exists before the code: nothing asked who the user is.
    expect(fetch.mock.calls.some(([request]) => request.url.endsWith('/users/me'))).toBe(false);
    const field = screen.getByLabelText('Code');
    expect(field.getAttribute('inputmode')).toBe('numeric');
    expect(field.getAttribute('autocomplete')).toBe('one-time-code');
    await enterCode();

    expect(await screen.findByRole('heading', { name: 'Inbox' })).toBeDefined();
    expect(router.state.location.pathname).toBe(PATHS.inbox);
    expect(await bodiesOf(fetch, '/mfa-verifications')).toEqual([
      { challengeToken: 'challenge-1', code: '123456' },
    ]);
  });

  it('does not send a code that has no six digits', async () => {
    const fetch = fakeApi({ ...challengeAnswer() });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode('12345');

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Enter the 6 digits the app shows.',
    );
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('says the code is wrong, clears it and lets the user try again', async () => {
    const answers = [problem(401, 'INVALID_MFA_CODE'), () => json(201, TOKENS)];
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': () => answers.shift()!(),
      'GET /users/me': () => json(200, OPERATOR),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode('000000');

    expect((await screen.findByRole('alert')).textContent).toBe(
      'That code is not correct. Check the app and type the one it shows now.',
    );
    expect((screen.getByLabelText('Code') as HTMLInputElement).value).toBe('');
    await enterCode();
    expect(await screen.findByRole('heading', { name: 'Inbox' })).toBeDefined();
    // The same challenge answered both: the password was not asked again.
    expect(await bodiesOf(fetch, '/mfa-verifications')).toEqual([
      { challengeToken: 'challenge-1', code: '000000' },
      { challengeToken: 'challenge-1', code: '123456' },
    ]);
    expect(await bodiesOf(fetch, '/sessions')).toHaveLength(1);
  });

  it('goes back to the password when the challenge has expired', async () => {
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': problem(401, 'INVALID_MFA_CHALLENGE'),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Your sign-in has expired. Enter your password again.',
    );
    expect(screen.getByLabelText('Password')).toBeDefined();
    // The password of the first attempt does not stay on the screen.
    expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('');
  });

  it('puts the focus on the password, which points at the message, when the challenge ends', async () => {
    renderApp(PATHS.signIn, {
      fetch: fakeApi({
        ...challengeAnswer(),
        'POST /mfa-verifications': problem(401, 'INVALID_MFA_CHALLENGE'),
      }),
    });

    await enterPassword();
    await enterCode();

    const password = await screen.findByLabelText('Password');
    await waitFor(() => expect(document.activeElement).toBe(password));
    expect(document.getElementById(password.getAttribute('aria-describedby')!)?.textContent).toBe(
      'Your sign-in has expired. Enter your password again.',
    );
    expect(password.getAttribute('aria-invalid')).toBeNull();
  });

  it('puts the focus on the password when the user goes back to sign in', async () => {
    renderApp(PATHS.signIn, { fetch: fakeApi({ ...challengeAnswer() }) });

    await enterPassword();
    await userEvent.click(await screen.findByRole('button', { name: 'Back to sign in' }));

    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Password')));
  });

  // The API spent the challenge with the code, and the user could not be read afterwards.
  it('goes back to the password, with no tokens kept, when the sign-in cannot finish', async () => {
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': () => json(201, TOKENS),
      'GET /users/me': problem(500),
      'DELETE /sessions/current': () => new Response(null, { status: 204 }),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Your code was accepted, but the sign-in could not be finished. Enter your password again.',
    );
    expect(screen.getByLabelText('Password')).toBeDefined();
    expect(screen.queryByLabelText('Code')).toBeNull();
    // The new session is revoked on the server, not only forgotten here.
    expect(fetch.mock.calls.some(([request]) => request.method === 'DELETE')).toBe(true);
  });

  it('accepts a code pasted with a space, such as 123 456', async () => {
    renderApp(PATHS.signIn, { fetch: fakeApi({ ...challengeAnswer() }) });

    await enterPassword();
    await userEvent.click(await screen.findByLabelText('Code'));
    await userEvent.paste('123 456');

    expect((screen.getByLabelText('Code') as HTMLInputElement).value).toBe('123456');
  });

  it('goes back to the password when the account was disabled meanwhile', async () => {
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': problem(403, 'ACCOUNT_DISABLED'),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'This account is disabled. Ask an administrator to enable it.',
    );
    expect(screen.getByLabelText('Password')).toBeDefined();
  });

  // #435: once the code step is locked no code can answer it, so the form does not ask for another.
  it('goes back to the password and names the administrator when the code step is locked', async () => {
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': problem(403, 'MFA_LOCKED'),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Too many wrong codes. Ask an administrator to reset your second factor, then set it up again.',
    );
    expect(screen.getByLabelText('Password')).toBeDefined();
    expect(screen.queryByLabelText('Code')).toBeNull();
  });

  it('tells the user to wait after too many wrong codes, and stays on the step', async () => {
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': problem(429, 'TOO_MANY_ATTEMPTS'),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Too many wrong codes. Wait a few minutes and try again.',
    );
    expect(screen.getByLabelText('Code')).toBeDefined();
    expect(screen.queryByLabelText('Password')).toBeNull();
  });

  // T15: a failed request must not cost the password while the challenge is valid.
  it.each([
    [
      'cannot be reached',
      () => {
        throw new TypeError('Failed to fetch');
      },
      'UrbanPulse cannot be reached. Check your connection and try again. Your password is still valid: press Verify to send the code again.',
    ],
    [
      'fails on the server',
      problem(500),
      'The code could not be checked. Try again in a moment. Your password is still valid.',
    ],
  ])('keeps the challenge and the code when the API %s', async (_name, failure, message) => {
    const answers = [failure, () => json(201, TOKENS)];
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': () => answers.shift()!(),
      'GET /users/me': () => json(200, OPERATOR),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();

    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(screen.queryByLabelText('Password')).toBeNull();
    expect((screen.getByLabelText('Code') as HTMLInputElement).value).toBe('123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByRole('heading', { name: 'Inbox' })).toBeDefined();
    expect(await bodiesOf(fetch, '/sessions')).toHaveLength(1);
    expect(await bodiesOf(fetch, '/mfa-verifications')).toEqual([
      { challengeToken: 'challenge-1', code: '123456' },
      { challengeToken: 'challenge-1', code: '123456' },
    ]);
  });

  it('sends one request even when the button is pressed twice', async () => {
    let release: () => void = () => undefined;
    const fetch = fakeApi({
      ...challengeAnswer(),
      'POST /mfa-verifications': () =>
        new Promise<Response>((resolve) => {
          release = () => resolve(problem(401, 'INVALID_MFA_CODE')());
        }),
    });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    await enterCode();
    await userEvent.click(await screen.findByRole('button', { name: 'Verifying…' }));
    release();

    await screen.findByText(/That code is not correct/);
    expect(await bodiesOf(fetch, '/mfa-verifications')).toHaveLength(1);
  });

  it('returns to the password step when the user asks', async () => {
    renderApp(PATHS.signIn, { fetch: fakeApi({ ...challengeAnswer() }) });

    await enterPassword();
    await userEvent.click(await screen.findByRole('button', { name: 'Back to sign in' }));

    expect(screen.getByLabelText('Password')).toBeDefined();
    expect(screen.queryByRole('alert')?.textContent ?? '').toBe('');
  });
});

describe('second factor enrolment', () => {
  const enrolling = (extra: Parameters<typeof fakeApi>[0] = {}) =>
    fakeApi({
      ...challengeAnswer(true),
      'POST /mfa-enrollments': () => json(201, ENROLLMENT),
      'POST /mfa-verifications': () => json(201, TOKENS),
      'GET /users/me': () => json(200, OPERATOR),
      ...extra,
    });

  it('shows the QR code and the secret as text, then asks for the first code', async () => {
    const fetch = enrolling();
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();

    expect(await screen.findByRole('heading', { name: 'Set up your second factor' })).toBeDefined();
    // The QR code is the otpauth URI; its text version is the secret, for manual entry.
    expect(await screen.findByRole('img', { name: /QR code to add UrbanPulse/ })).toBeDefined();
    expect(screen.getByText(ENROLLMENT.secret)).toBeDefined();
    expect(await bodiesOf(fetch, '/mfa-enrollments')).toEqual([{ challengeToken: 'challenge-1' }]);
    await enterCode();

    expect(await screen.findByRole('heading', { name: 'Inbox' })).toBeDefined();
  });

  it('asks for the code only after the secret is shown', async () => {
    let release: () => void = () => undefined;
    renderApp(PATHS.signIn, {
      fetch: enrolling({
        'POST /mfa-enrollments': () =>
          new Promise<Response>((resolve) => {
            release = () => resolve(json(201, ENROLLMENT));
          }),
      }),
    });

    await enterPassword();

    expect(await screen.findByText('Preparing the setup…')).toBeDefined();
    expect(screen.queryByLabelText('Code')).toBeNull();
    release();
    expect(await screen.findByLabelText('Code')).toBeDefined();
  });

  it('offers to try the setup again, keeping the challenge, when it could not start', async () => {
    const answers = [problem(500), () => json(201, ENROLLMENT)];
    const fetch = enrolling({ 'POST /mfa-enrollments': () => answers.shift()!() });
    renderApp(PATHS.signIn, { fetch });

    await enterPassword();
    expect((await screen.findByRole('alert')).textContent).toContain(
      'The setup could not be started. Try again in a moment.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByLabelText('Code')).toBeDefined();
    expect(await bodiesOf(fetch, '/mfa-enrollments')).toEqual([
      { challengeToken: 'challenge-1' },
      { challengeToken: 'challenge-1' },
    ]);
    expect(await bodiesOf(fetch, '/sessions')).toHaveLength(1);
  });

  it('goes back to the password when the challenge is lost before the setup', async () => {
    renderApp(PATHS.signIn, {
      fetch: enrolling({ 'POST /mfa-enrollments': problem(401, 'INVALID_MFA_CHALLENGE') }),
    });

    await enterPassword();

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        'Your sign-in has expired. Enter your password again.',
      ),
    );
    expect(screen.getByLabelText('Password')).toBeDefined();
  });

  it('asks only for the code when the account turns out to be enrolled already', async () => {
    renderApp(PATHS.signIn, {
      fetch: enrolling({ 'POST /mfa-enrollments': problem(409, 'MFA_ALREADY_ENROLLED') }),
    });

    await enterPassword();

    expect(await screen.findByLabelText('Code')).toBeDefined();
    expect(screen.queryByRole('img', { name: /QR code/ })).toBeNull();
    // With no QR code to scan, the set-up texts would be wrong: the normal ones are shown.
    expect(screen.getByRole('heading', { name: 'Enter your code' })).toBeDefined();
    expect(screen.getByText('Type the code your authenticator app shows now.')).toBeDefined();
  });
});
