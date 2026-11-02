import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';
import { fireEvent, render, screen, userEvent, waitFor } from '@testing-library/react-native';

import type { AuthStackParamList } from '../navigation/AuthStack';
import { useSession } from '../session/SessionContext';
import { SignInScreen } from './SignInScreen';

jest.mock('../session/SessionContext', () => ({ useSession: jest.fn() }));

type Props = NativeStackScreenProps<AuthStackParamList, 'SignIn'>;

const mockedUseSession = jest.mocked(useSession);
const signIn = jest.fn<Promise<ApiResult<void>>, [{ email: string; password: string }]>();

function failure(failure: ApiFailure): ApiResult<void> {
  return { ok: false, failure };
}

async function renderScreen({
  expired = false,
  notice,
}: { expired?: boolean; notice?: string } = {}) {
  mockedUseSession.mockReturnValue({ expired, signIn } as unknown as ReturnType<typeof useSession>);
  const navigation = { navigate: jest.fn() };
  await render(
    <SignInScreen
      navigation={navigation as unknown as Props['navigation']}
      route={{ key: 'SignIn', name: 'SignIn', params: notice ? { notice } : undefined }}
    />,
  );
  return { navigation };
}

async function signInWith(email: string, password: string) {
  await fireEvent.changeText(screen.getByLabelText('Email'), email);
  await fireEvent.changeText(screen.getByLabelText('Password'), password);
  await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  signIn.mockReset();
});

describe('SignInScreen', () => {
  it('signs in with the trimmed email and the password as typed', async () => {
    signIn.mockResolvedValue({ ok: true, data: undefined });
    await renderScreen();

    await signInWith('  lucia.martin@example.com ', ' secret password ');

    expect(signIn).toHaveBeenCalledWith({
      email: 'lucia.martin@example.com',
      password: ' secret password ',
    });
    expect(screen.queryByText(/not correct|cannot be reached|Too many/)).not.toBeOnTheScreen();
  });

  it.each([
    [
      'wrong credentials',
      { kind: 'problem', status: 401, code: 'INVALID_CREDENTIALS' },
      'The email or the password is not correct.',
    ],
    [
      'a disabled account',
      { kind: 'problem', status: 403, code: 'ACCOUNT_DISABLED' },
      'This account is disabled. Contact the city council.',
    ],
    [
      'too many attempts',
      { kind: 'problem', status: 429, code: undefined },
      'Too many attempts. Wait a few minutes and try again.',
    ],
    [
      'an unreachable API',
      { kind: 'network' },
      'UrbanPulse cannot be reached. Check your connection and try again.',
    ],
  ] as const)('explains %s', async (_case, apiFailure, message) => {
    signIn.mockResolvedValue(failure(apiFailure as ApiFailure));
    await renderScreen();

    await signInWith('lucia.martin@example.com', 'wrong password');

    expect(await screen.findByText(message)).toBeOnTheScreen();
  });

  it('does not sign in twice while the first attempt is in flight', async () => {
    signIn.mockReturnValue(new Promise(() => undefined));
    const user = userEvent.setup();
    await renderScreen();
    await fireEvent.changeText(screen.getByLabelText('Email'), 'lucia.martin@example.com');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret password');

    await user.press(screen.getByRole('button', { name: 'Sign in' }));
    const signingIn = await screen.findByRole('button', { name: 'Signing in…' });
    await user.press(signingIn);

    expect(signingIn).toBeDisabled();
    expect(signIn).toHaveBeenCalledTimes(1);
  });

  it('says why the user has to sign in again', async () => {
    await renderScreen({ expired: true });
    expect(screen.getByText('Your session has expired. Sign in again.')).toBeOnTheScreen();

    await renderScreen({ notice: 'Your account is ready. Sign in to continue.' });
    expect(screen.getByText('Your account is ready. Sign in to continue.')).toBeOnTheScreen();
  });

  it('replaces the notice with the error of a failed attempt', async () => {
    signIn.mockResolvedValue(
      failure({ kind: 'problem', status: 401, code: 'INVALID_CREDENTIALS' }),
    );
    await renderScreen({ expired: true });

    await signInWith('lucia.martin@example.com', 'wrong password');

    await waitFor(() =>
      expect(screen.queryByText('Your session has expired. Sign in again.')).not.toBeOnTheScreen(),
    );
    expect(screen.getByText('The email or the password is not correct.')).toBeOnTheScreen();
  });

  it('opens the sign-up screen', async () => {
    const { navigation } = await renderScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Create an account' }));

    expect(navigation.navigate).toHaveBeenCalledWith('SignUp');
  });
});
