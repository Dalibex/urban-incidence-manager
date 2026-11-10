import { createApiClient, getCurrentUser, signOut } from '@urbanpulse/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react-native';

import { secureTokenStore } from './secure-token-store';
import { SessionProvider, useSession } from './SessionContext';

jest.mock('@urbanpulse/api-client', () => ({
  createApiClient: jest.fn(() => ({})),
  getCurrentUser: jest.fn(),
  signIn: jest.fn(),
  signOut: jest.fn(),
  signUp: jest.fn(),
}));
jest.mock('./secure-token-store', () => ({
  secureTokenStore: { load: jest.fn(async () => null), clear: jest.fn(async () => undefined) },
}));

type Session = ReturnType<typeof useSession>;

const SAVED = {
  tokenType: 'Bearer' as const,
  accessToken: 'access',
  accessTokenExpiresAt: '2026-11-08T10:15:00Z',
  refreshToken: 'refresh',
  refreshTokenExpiresAt: '2026-11-22T10:00:00Z',
};

/** Renders the real provider over a cache that holds an incident of the signed-in user. */
async function renderSession() {
  // An infinite gcTime schedules no garbage collection timer, which would outlive a test that
  // keeps the cache.
  const queryClient = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
  queryClient.setQueryData(['incidents', 'a'], { id: 'a', title: 'Street light off' });
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
  return { queryClient, session: () => session as Session };
}

describe('SessionProvider', () => {
  // Calls only: the implementations of the module mocks stay.
  beforeEach(() => jest.clearAllMocks());

  it('forgets the cached incidents when the user signs out', async () => {
    jest.mocked(signOut).mockResolvedValue(undefined);
    const { queryClient, session } = await renderSession();

    await act(() => session().signOut());

    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('forgets the cached incidents when the session expires', async () => {
    const { queryClient } = await renderSession();
    // The client this provider created, not the one of an earlier test.
    const [, options] = jest.mocked(createApiClient).mock.lastCall ?? [];

    await act(async () => options?.onSessionExpired?.());

    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  // Not a case of #164: the API rejected the access token and the refresh failed without an answer,
  // so the client kept the tokens and the call still ends in 401.
  it('keeps the session when the check ends in 401 but the tokens are still saved', async () => {
    // Read before the check and again after it.
    jest.mocked(secureTokenStore.load).mockResolvedValueOnce(SAVED).mockResolvedValueOnce(SAVED);
    jest.mocked(getCurrentUser).mockResolvedValueOnce({
      ok: false,
      failure: { kind: 'problem', status: 401, code: undefined },
    });

    const { session } = await renderSession();

    expect(session().restore).toEqual({
      status: 'failed',
      failure: { kind: 'problem', status: 401, code: undefined },
      retrying: false,
    });
    expect(session().user).toBeNull();
    expect(secureTokenStore.clear).not.toHaveBeenCalled();
  });

  // Expo's `fetch` rejects with a `FetchError`, which extends `Error`, and `callApi` rethrows it
  // (#244). Without network that must keep the session, not discard it as unreadable.
  it('keeps the session when the check throws an error the client does not recognize', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    jest.mocked(secureTokenStore.load).mockResolvedValueOnce(SAVED);
    jest
      .mocked(getCurrentUser)
      .mockRejectedValueOnce(
        new Error('fetch failed: The Internet connection appears to be offline.'),
      );

    const { session } = await renderSession();

    expect(session().restore).toEqual({
      status: 'failed',
      failure: { kind: 'network' },
      retrying: false,
    });
    expect(secureTokenStore.clear).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('discards a saved session that cannot be read', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    jest.mocked(secureTokenStore.load).mockRejectedValueOnce(new Error('Could not decrypt'));

    const { session } = await renderSession();

    expect(session().restore).toEqual({ status: 'done' });
    expect(secureTokenStore.clear).toHaveBeenCalledTimes(1);
    expect(getCurrentUser).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});
