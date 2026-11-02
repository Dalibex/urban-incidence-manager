import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';
import { fireEvent, render, screen, userEvent } from '@testing-library/react-native';

import type { AuthStackParamList } from '../navigation/AuthStack';
import { useSession } from '../session/SessionContext';
import { SignUpScreen } from './SignUpScreen';

jest.mock('../session/SessionContext', () => ({ useSession: jest.fn() }));

type Props = NativeStackScreenProps<AuthStackParamList, 'SignUp'>;

const mockedUseSession = jest.mocked(useSession);
const signUp = jest.fn<Promise<ApiResult<unknown>>, [unknown]>();
const signIn = jest.fn<Promise<ApiResult<void>>, [unknown]>();

const ACCOUNT = {
  id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
  name: 'Lucía Martín',
  email: 'lucia.martin@example.com',
  role: 'CITIZEN',
};

function failure(failure: ApiFailure): ApiResult<never> {
  return { ok: false, failure };
}

async function renderScreen() {
  mockedUseSession.mockReturnValue({ signUp, signIn } as unknown as ReturnType<typeof useSession>);
  const navigation = { replace: jest.fn() };
  await render(
    <SignUpScreen
      navigation={navigation as unknown as Props['navigation']}
      route={{ key: 'SignUp', name: 'SignUp' }}
    />,
  );
  return { navigation };
}

async function fillIn(name: string, email: string, password: string) {
  await fireEvent.changeText(screen.getByLabelText('Full name'), name);
  await fireEvent.changeText(screen.getByLabelText('Email'), email);
  await fireEvent.changeText(screen.getByLabelText('Password'), password);
}

beforeEach(() => {
  signUp.mockReset();
  signIn.mockReset();
});

describe('SignUpScreen', () => {
  it('creates the account and signs in with the same credentials', async () => {
    signUp.mockResolvedValue({ ok: true, data: ACCOUNT });
    signIn.mockResolvedValue({ ok: true, data: undefined });
    const { navigation } = await renderScreen();
    await fillIn(' Lucía Martín ', ' lucia.martin@example.com ', 'secret password');

    await fireEvent.press(screen.getByRole('button', { name: 'Create account' }));

    expect(signUp).toHaveBeenCalledWith({
      name: 'Lucía Martín',
      email: 'lucia.martin@example.com',
      password: 'secret password',
    });
    expect(signIn).toHaveBeenCalledWith({
      email: 'lucia.martin@example.com',
      password: 'secret password',
    });
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it.each([
    [
      'invalid fields',
      { kind: 'problem', status: 422, code: 'VALIDATION_FAILED' },
      'Check your name, a valid email and a password of at least 8 characters.',
    ],
    [
      'an email already in use',
      { kind: 'problem', status: 409, code: 'EMAIL_ALREADY_EXISTS' },
      'An account with this email already exists. Sign in instead.',
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
  ] as const)('explains %s and does not sign in', async (_case, apiFailure, message) => {
    signUp.mockResolvedValue(failure(apiFailure as ApiFailure));
    await renderScreen();
    await fillIn('Lucía Martín', 'lucia.martin@example.com', 'short');

    await fireEvent.press(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText(message)).toBeOnTheScreen();
    expect(signIn).not.toHaveBeenCalled();
  });

  it('sends the user to sign in when the account exists but signing in fails', async () => {
    signUp.mockResolvedValue({ ok: true, data: ACCOUNT });
    signIn.mockResolvedValue(failure({ kind: 'network' }));
    const { navigation } = await renderScreen();
    await fillIn('Lucía Martín', 'lucia.martin@example.com', 'secret password');

    await fireEvent.press(screen.getByRole('button', { name: 'Create account' }));

    expect(navigation.replace).toHaveBeenCalledWith('SignIn', {
      notice: 'Your account is ready. Sign in to continue.',
    });
  });

  it('does not create the account twice while the first attempt is in flight', async () => {
    signUp.mockReturnValue(new Promise(() => undefined));
    const user = userEvent.setup();
    await renderScreen();
    await fillIn('Lucía Martín', 'lucia.martin@example.com', 'secret password');

    await user.press(screen.getByRole('button', { name: 'Create account' }));
    const creating = await screen.findByRole('button', { name: 'Creating account…' });
    await user.press(creating);

    expect(creating).toBeDisabled();
    expect(signUp).toHaveBeenCalledTimes(1);
  });
});
