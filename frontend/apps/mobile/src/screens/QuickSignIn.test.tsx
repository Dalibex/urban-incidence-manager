import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';
import { fireEvent, render, screen, userEvent } from '@testing-library/react-native';

import type { AuthStackParamList } from '../navigation/AuthStack';
import { useSession } from '../session/SessionContext';
import { QUICK_SIGN_IN_CITIZEN_EMAIL } from './quick-sign-in';
import { SignInScreen } from './SignInScreen';

// Acceptance tests of #168 for the quick sign-in of the sign-in screen (#167). jest.setup.ts turns
// the flag off for every test; these turn it on by setting the variables.

jest.mock('../session/SessionContext', () => ({ useSession: jest.fn() }));

type Props = NativeStackScreenProps<AuthStackParamList, 'SignIn'>;

const DEMO_PASSWORD = 'demo-password-for-the-test';
const QUICK_BUTTON = 'Quick sign-in as the demo citizen';
const MISSING_PASSWORD = 'Set EXPO_PUBLIC_DEMO_PASSWORD in apps/mobile/.env to use quick sign-in.';

const VARIABLES = ['EXPO_PUBLIC_QUICK_SIGN_IN', 'EXPO_PUBLIC_DEMO_PASSWORD'];
const original = Object.fromEntries(VARIABLES.map((name) => [name, process.env[name]]));

const mockedUseSession = jest.mocked(useSession);
const signIn = jest.fn<Promise<ApiResult<void>>, [{ email: string; password: string }]>();

// Expo reads the env through a reference to `process.env`: change its keys, never replace it.
function setEnv(name: string, value: string | undefined) {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}

// No default value: `undefined` is a case of its own (T5), the variable absent.
function quickSignIn(password: string | undefined) {
  setEnv('EXPO_PUBLIC_QUICK_SIGN_IN', 'true');
  setEnv('EXPO_PUBLIC_DEMO_PASSWORD', password);
}

function failure(apiFailure: ApiFailure): ApiResult<void> {
  return { ok: false, failure: apiFailure };
}

async function renderScreen() {
  mockedUseSession.mockReturnValue({ expired: false, signIn } as unknown as ReturnType<
    typeof useSession
  >);
  await render(
    <SignInScreen
      navigation={{ navigate: jest.fn() } as unknown as Props['navigation']}
      route={{ key: 'SignIn', name: 'SignIn', params: undefined }}
    />,
  );
}

beforeEach(() => {
  signIn.mockReset();
});

afterEach(() => {
  VARIABLES.forEach((name) => setEnv(name, original[name]));
});

describe('QuickSignIn', () => {
  // T3
  it('shows nothing with the flag off', async () => {
    setEnv('EXPO_PUBLIC_DEMO_PASSWORD', DEMO_PASSWORD);
    await renderScreen();

    expect(screen.queryByRole('button', { name: QUICK_BUTTON })).not.toBeOnTheScreen();
    expect(screen.queryByText(MISSING_PASSWORD)).not.toBeOnTheScreen();
  });

  // T3, and T1 without a backend: the button signs in as the demo citizen.
  it('signs in as the demo citizen with the flag on', async () => {
    quickSignIn(DEMO_PASSWORD);
    signIn.mockResolvedValue({ ok: true, data: undefined });
    await renderScreen();

    await fireEvent.press(screen.getByRole('button', { name: QUICK_BUTTON }));

    expect(signIn).toHaveBeenCalledWith({
      email: QUICK_SIGN_IN_CITIZEN_EMAIL,
      password: DEMO_PASSWORD,
    });
    expect(QUICK_SIGN_IN_CITIZEN_EMAIL).toBe('demo.citizen@example.com');
  });

  // T4
  it('shows the normal error with a wrong password', async () => {
    quickSignIn('not-the-demo-password');
    signIn.mockResolvedValue(
      failure({ kind: 'problem', status: 401, code: 'INVALID_CREDENTIALS' }),
    );
    await renderScreen();

    await fireEvent.press(screen.getByRole('button', { name: QUICK_BUTTON }));

    expect(await screen.findByText('The email or the password is not correct.')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: QUICK_BUTTON })).toBeEnabled();
  });

  // T5
  it.each([
    ['empty', ''],
    ['absent', undefined],
  ])('says what is missing when the password is %s, and sends nothing', async (_, password) => {
    quickSignIn(password);
    await renderScreen();

    expect(screen.getByText(MISSING_PASSWORD)).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: QUICK_BUTTON })).not.toBeOnTheScreen();
    expect(signIn).not.toHaveBeenCalled();
  });

  // T7
  it.each([
    [
      'no network',
      { kind: 'network' },
      'UrbanPulse cannot be reached. Check your connection and try again.',
    ],
    [
      'a 5xx',
      { kind: 'problem', status: 502, code: undefined },
      'Sign-in failed. Try again in a moment.',
    ],
  ] as const)(
    'shows the normal error with %s and lets the user retry',
    async (_, apiFailure, message) => {
      quickSignIn(DEMO_PASSWORD);
      signIn
        .mockResolvedValueOnce(failure(apiFailure as ApiFailure))
        .mockResolvedValueOnce({ ok: true, data: undefined });
      await renderScreen();

      await fireEvent.press(screen.getByRole('button', { name: QUICK_BUTTON }));

      expect(await screen.findByText(message)).toBeOnTheScreen();
      const button = screen.getByRole('button', { name: QUICK_BUTTON });
      expect(button).toBeEnabled();

      await fireEvent.press(button);

      expect(signIn).toHaveBeenCalledTimes(2);
    },
  );

  // T8
  it('sends one request on a double tap', async () => {
    quickSignIn(DEMO_PASSWORD);
    signIn.mockReturnValue(new Promise(() => undefined));
    const user = userEvent.setup();
    await renderScreen();

    const button = screen.getByRole('button', { name: QUICK_BUTTON });
    await user.press(button);
    await user.press(button);

    expect(button).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    expect(signIn).toHaveBeenCalledTimes(1);
  });
});
