import {
  deletePushToken,
  getCurrentUser,
  getNotificationSettings,
  registerPushToken,
  signIn,
  signOut,
} from '@urbanpulse/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react-native';

import { readPushPermission, readPushToken } from '../notifications/push';
import { secureTokenStore } from './secure-token-store';
import { SessionProvider, useSession } from './SessionContext';

jest.mock('@urbanpulse/api-client', () => ({
  createApiClient: jest.fn(() => ({})),
  getCurrentUser: jest.fn(),
  getNotificationSettings: jest.fn(),
  registerPushToken: jest.fn(),
  deletePushToken: jest.fn(),
  signIn: jest.fn(),
  signOut: jest.fn(),
  signUp: jest.fn(),
}));
jest.mock('./secure-token-store', () => ({
  secureTokenStore: { load: jest.fn(), clear: jest.fn() },
}));
jest.mock('../notifications/push', () => ({
  readPushPermission: jest.fn(),
  askPushPermission: jest.fn(),
  readPushToken: jest.fn(),
}));

type Session = ReturnType<typeof useSession>;

const SAVED = {
  tokenType: 'Bearer' as const,
  accessToken: 'access-before-sign-out',
  accessTokenExpiresAt: '2026-11-08T10:15:00Z',
  refreshToken: 'refresh',
  refreshTokenExpiresAt: '2026-11-22T10:00:00Z',
};
const USER = {
  id: 'u-1',
  name: 'Lucía Martín',
  email: 'lucia@example.com',
  role: 'CITIZEN' as const,
  enabled: true,
};
const DEVICE_TOKEN = 'ExponentPushToken[device-1]';
const NETWORK_FAILURE = { ok: false as const, failure: { kind: 'network' as const } };

/** A device with nothing saved; each test adds the session or the settings it needs. */
beforeEach(() => {
  jest.resetAllMocks();
  jest.mocked(secureTokenStore.load).mockResolvedValue(null);
  jest.mocked(secureTokenStore.clear).mockResolvedValue(undefined);
  jest.mocked(signOut).mockResolvedValue(undefined);
  jest.mocked(registerPushToken).mockResolvedValue({ ok: true, data: undefined });
  jest.mocked(deletePushToken).mockResolvedValue({ ok: true, data: undefined });
  jest.mocked(readPushPermission).mockResolvedValue('granted');
  jest.mocked(readPushToken).mockResolvedValue(DEVICE_TOKEN);
  jest.mocked(getCurrentUser).mockResolvedValue({ ok: true, data: USER });
});

function pushSetting(push: boolean) {
  jest.mocked(getNotificationSettings).mockResolvedValue({ ok: true, data: { push, email: true } });
}

function savedSession() {
  jest.mocked(secureTokenStore.load).mockResolvedValue(SAVED);
}

async function renderSession() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
  let session: Session | undefined;
  function Probe() {
    session = useSession();
    return null;
  }
  await render(
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <Probe />
      </SessionProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(session?.restore.status).not.toBe('checking'));
  return () => session as Session;
}

/** Lets the work that sign-in and start-up leave running in the background finish. */
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

async function signInAsCitizen(session: () => Session) {
  jest.mocked(signIn).mockResolvedValue({ ok: true, data: null });
  const result = await act(() => session().signIn({ email: USER.email, password: 'secret-1' }));
  await settle();
  return result;
}

describe('push token at sign-in', () => {
  it('registers the device token when push is on and the permission is granted', async () => {
    // Arrange
    pushSetting(true);
    const session = await renderSession();

    // Act
    await signInAsCitizen(session);

    // Assert
    expect(registerPushToken).toHaveBeenCalledTimes(1);
    expect(jest.mocked(registerPushToken).mock.calls[0][1]).toBe(DEVICE_TOKEN);
  });

  it('does not register a token when the permission was not granted', async () => {
    // Arrange
    pushSetting(true);
    jest.mocked(readPushPermission).mockResolvedValue('denied');
    const session = await renderSession();

    // Act
    await signInAsCitizen(session);

    // Assert
    expect(registerPushToken).not.toHaveBeenCalled();
  });

  it('deletes the token of the device when push is off, as it may belong to the previous citizen', async () => {
    // Arrange
    pushSetting(false);
    const session = await renderSession();

    // Act
    await signInAsCitizen(session);

    // Assert
    expect(registerPushToken).not.toHaveBeenCalled();
    expect(deletePushToken).toHaveBeenCalledTimes(1);
    expect(jest.mocked(deletePushToken).mock.calls[0][1]).toBe(DEVICE_TOKEN);
  });

  it('deletes nothing when push is off and the device has no readable token', async () => {
    // Arrange
    pushSetting(false);
    jest.mocked(readPushToken).mockResolvedValue(null);
    const session = await renderSession();

    // Act
    await signInAsCitizen(session);

    // Assert
    expect(deletePushToken).not.toHaveBeenCalled();
  });

  it('signs in even when the token cannot be read', async () => {
    // Arrange
    pushSetting(true);
    jest.mocked(readPushToken).mockRejectedValue(new Error('Expo Go has no push token'));
    const session = await renderSession();

    // Act
    const result = await signInAsCitizen(session);

    // Assert
    expect(result).toEqual({ ok: true, data: undefined });
    expect(session().user).toEqual(USER);
  });

  it('signs in even when the settings cannot be read', async () => {
    // Arrange
    jest.mocked(getNotificationSettings).mockResolvedValue(NETWORK_FAILURE);
    const session = await renderSession();

    // Act
    const result = await signInAsCitizen(session);

    // Assert
    expect(result).toEqual({ ok: true, data: undefined });
    expect(session().user).toEqual(USER);
  });
});

describe('push token at start', () => {
  it('registers the device token when the saved session has push on and the permission granted', async () => {
    // Arrange
    savedSession();
    pushSetting(true);

    // Act
    const session = await renderSession();
    await settle();

    // Assert
    expect(session().user).toEqual(USER);
    expect(registerPushToken).toHaveBeenCalledTimes(1);
    expect(jest.mocked(registerPushToken).mock.calls[0][1]).toBe(DEVICE_TOKEN);
  });

  it('starts signed in even when the token cannot be read', async () => {
    // Arrange
    savedSession();
    pushSetting(true);
    jest.mocked(readPushToken).mockRejectedValue(new Error('Expo Go has no push token'));

    // Act
    const session = await renderSession();
    await settle();

    // Assert
    expect(session().restore).toEqual({ status: 'done' });
    expect(session().user).toEqual(USER);
  });

  it('does not register anything when nobody is signed in', async () => {
    // Arrange
    pushSetting(true);

    // Act
    await renderSession();
    await settle();

    // Assert
    expect(registerPushToken).not.toHaveBeenCalled();
  });
});

describe('push token at sign-out', () => {
  /** The signed-in citizen, with the calls of the API in the order they were made. */
  async function signedInSession() {
    savedSession();
    pushSetting(true);
    const session = await renderSession();
    await settle();
    jest.mocked(registerPushToken).mockClear();
    const calls: string[] = [];
    jest.mocked(signOut).mockImplementation(async () => {
      calls.push('DELETE /sessions/current');
      // The client clears the tokens: after it, the store no longer has the access token.
      jest.mocked(secureTokenStore.load).mockResolvedValue(null);
    });
    jest.mocked(deletePushToken).mockImplementation(async () => {
      calls.push('POST /push-token-deletions');
      return { ok: true, data: undefined };
    });
    return { session, calls };
  }

  it('deletes the device token after revoking the session, with the access token it had before (F2)', async () => {
    // Arrange
    const { session, calls } = await signedInSession();

    // Act
    await act(() => session().signOut());

    // Assert
    expect(calls).toEqual(['DELETE /sessions/current', 'POST /push-token-deletions']);
    const [, token, accessToken] = jest.mocked(deletePushToken).mock.calls[0];
    expect(token).toBe(DEVICE_TOKEN);
    expect(accessToken).toBe('access-before-sign-out');
  });

  it('deletes the device token with the refreshed access token when the sign-out had to refresh', async () => {
    // Arrange
    const { session, calls } = await signedInSession();
    const refreshed = { ...SAVED, accessToken: 'access-after-refresh', refreshToken: 'refresh-2' };
    // The stored access token has expired: the DELETE answers 401 once, the client refreshes,
    // repeats the DELETE with the new tokens and hands them to the callback before clearing.
    jest.mocked(signOut).mockImplementation(async (_client, _store, afterDelete) => {
      calls.push(
        'DELETE /sessions/current (401)',
        'POST /sessions/refresh',
        'DELETE /sessions/current',
      );
      jest.mocked(secureTokenStore.load).mockResolvedValue(refreshed);
      await afterDelete?.(refreshed);
      jest.mocked(secureTokenStore.load).mockResolvedValue(null);
    });

    // Act
    await act(() => session().signOut());

    // Assert
    expect(calls).toEqual([
      'DELETE /sessions/current (401)',
      'POST /sessions/refresh',
      'DELETE /sessions/current',
      'POST /push-token-deletions',
    ]);
    const [, token, accessToken] = jest.mocked(deletePushToken).mock.calls[0];
    expect(token).toBe(DEVICE_TOKEN);
    expect(accessToken).toBe('access-after-refresh');
  });

  it('signs out when the token deletion fails for lack of network', async () => {
    // Arrange
    const { session } = await signedInSession();
    jest.mocked(deletePushToken).mockResolvedValue(NETWORK_FAILURE);

    // Act
    await act(() => session().signOut());

    // Assert
    expect(session().user).toBeNull();
  });

  it('signs out when the token deletion throws', async () => {
    // Arrange
    const { session } = await signedInSession();
    jest.mocked(deletePushToken).mockRejectedValue(new Error('Unexpected'));

    // Act
    await act(() => session().signOut());

    // Assert
    expect(session().user).toBeNull();
  });

  it('signs out when the token cannot be read', async () => {
    // Arrange
    const { session } = await signedInSession();
    jest.mocked(readPushToken).mockRejectedValue(new Error('Expo Go has no push token'));

    // Act
    await act(() => session().signOut());

    // Assert
    expect(session().user).toBeNull();
    expect(deletePushToken).not.toHaveBeenCalled();
  });
});
