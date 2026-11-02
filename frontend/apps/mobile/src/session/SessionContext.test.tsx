import { createApiClient, signOut } from '@urbanpulse/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react-native';

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

/** Renders the real provider over a cache that holds an incident of the signed-in user. */
async function renderSession() {
  const queryClient = new QueryClient();
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
  await waitFor(() => expect(session?.restoring).toBe(false));
  return { queryClient, session: () => session as Session };
}

describe('SessionProvider', () => {
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
});
