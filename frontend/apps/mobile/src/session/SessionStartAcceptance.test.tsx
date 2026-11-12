import { NavigationContainer } from '@react-navigation/native';
import { act, screen, userEvent, waitFor } from '@testing-library/react-native';
import { FetchError } from 'expo/build/winter/fetch/FetchErrors';
import * as SecureStore from 'expo-secure-store';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { RootNavigator } from '../navigation/RootNavigator';
import { renderWithQueries } from '../test-utils';
import { SESSION_CHECK_TIMEOUT_MS, SessionProvider } from './SessionContext';

// Acceptance tests of #164 for the session check at start-up (#163): T1 to T9. The app is the real
// one from the session down (provider, API client, token store, navigator and screens); only the
// network (`fetch`), the secure store of the phone and the tabs of a signed-in citizen are replaced.
// T10 needs the server and is a manual case.

jest.mock('expo-secure-store', () => {
  const items = new Map<string, string>();
  return {
    mockItems: items,
    getItemAsync: jest.fn(async (key: string) => items.get(key) ?? null),
    setItemAsync: jest.fn(async (key: string, value: string) => {
      items.set(key, value);
    }),
    deleteItemAsync: jest.fn(async (key: string) => {
      items.delete(key);
    }),
  };
});

// The tabs of a signed-in citizen are not what these cases check: a stand-in says who signed in.
jest.mock('../navigation/MainTabs', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const { useSession } = jest.requireActual<typeof import('./SessionContext')>('./SessionContext');
  return {
    MainTabs: function MainTabs() {
      return <Text>Signed in as {useSession().user?.name}</Text>;
    },
  };
});

const TOKENS_KEY = 'urbanpulse.session';
const items = (SecureStore as unknown as { mockItems: Map<string, string> }).mockItems;

const SAVED = {
  tokenType: 'Bearer',
  accessToken: 'saved-access',
  accessTokenExpiresAt: '2026-11-11T10:15:00Z',
  refreshToken: 'saved-refresh',
  refreshTokenExpiresAt: '2026-11-25T10:00:00Z',
};

const REFRESHED = {
  ...SAVED,
  accessToken: 'refreshed-access',
  refreshToken: 'refreshed-refresh',
};

const CITIZEN = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Lucía Citizen',
  email: 'lucia@example.com',
  role: 'CITIZEN',
};

const NETWORK_MESSAGE = 'UrbanPulse cannot be reached. Check your connection and try again.';
const CHECK_MESSAGE = 'Your session could not be checked. Try again in a moment.';
const EXPIRED_MESSAGE = 'Your session has expired. Sign in again.';

type Answer = (request: Request) => Response | Promise<Response>;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
}

const NO_NETWORK: Answer = () => {
  throw new TypeError('Network request failed');
};

const NEVER: Answer = () => new Promise<Response>(() => undefined);

const ME: Answer = () => json(200, CITIZEN);

/** The API: each operation answers with its handler; the requests are kept to count them. */
function api(handlers: Record<string, Answer>) {
  const requests: string[] = [];
  const fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const operation = `${request.method} ${new URL(request.url).pathname.replace('/api/v1', '')}`;
    requests.push(operation);
    const handler = handlers[operation];
    if (!handler) {
      throw new Error(`Unexpected request ${operation}`);
    }
    return handler(request);
  });
  globalThis.fetch = fetch as typeof globalThis.fetch;
  return {
    requests,
    count: (operation: string) => requests.filter((sent) => sent === operation).length,
    /** Replaces the answers, as when the network comes back. */
    answer: (next: Record<string, Answer>) => Object.assign(handlers, next),
  };
}

function savedTokens() {
  const saved = items.get(TOKENS_KEY);
  return saved ? JSON.parse(saved) : null;
}

async function startApp() {
  await renderWithQueries(
    <SafeAreaProvider
      initialMetrics={{
        frame: { x: 0, y: 0, width: 390, height: 844 },
        insets: { top: 0, left: 0, right: 0, bottom: 0 },
      }}
    >
      <SessionProvider>
        <NavigationContainer>
          <RootNavigator />
        </NavigationContainer>
      </SessionProvider>
    </SafeAreaProvider>,
  );
}

async function expectTheSignInScreen() {
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeOnTheScreen();
}

async function expectTheCheckFailed(message: string) {
  expect(await screen.findByText(message)).toBeOnTheScreen();
  expect(screen.getByRole('button', { name: 'Try again' })).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeOnTheScreen();
}

const originalFetch = globalThis.fetch;
let warn: jest.SpyInstance;

beforeEach(() => {
  items.clear();
  jest.clearAllMocks();
  // The provider warns when a check throws or a saved session cannot be read.
  warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  warn.mockRestore();
  jest.useRealTimers();
});

describe('session check at start-up, acceptance of #164', () => {
  // T1
  it('opens signed in when it starts with network', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({ 'GET /users/me': ME });

    await startApp();

    expect(await screen.findByText('Signed in as Lucía Citizen')).toBeOnTheScreen();
    expect(server.count('POST /sessions')).toBe(0);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T1: the access token expired while the app was closed; the refresh keeps the user signed in.
  it('opens signed in after refreshing a token that expired while the app was closed', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({
      'GET /users/me': (request) =>
        request.headers.get('Authorization') === 'Bearer refreshed-access'
          ? json(200, CITIZEN)
          : json(401, { status: 401 }),
      'POST /sessions/refresh': () => json(200, REFRESHED),
    });

    await startApp();

    expect(await screen.findByText('Signed in as Lucía Citizen')).toBeOnTheScreen();
    expect(server.count('POST /sessions/refresh')).toBe(1);
    expect(savedTokens()).toEqual(REFRESHED);
  });

  // T2
  it('offers to try again without network, and keeps the tokens', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({ 'GET /users/me': NO_NETWORK });

    await startApp();

    await expectTheCheckFailed(NETWORK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
    expect(server.count('POST /sessions')).toBe(0);
  });

  // T2: Expo's `fetch` rejects with a FetchError, not a TypeError (#244).
  it('offers to try again when Expo reports the missing network with an error of its own', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({
      'GET /users/me': () => {
        throw new FetchError('The Internet connection appears to be offline.');
      },
    });

    await startApp();

    await expectTheCheckFailed(NETWORK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T2: the access token expired while the app was closed, and the refresh cannot reach the API.
  it('keeps the session when the refresh of an expired token fails without network', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({
      'GET /users/me': () => json(401, { status: 401 }),
      'POST /sessions/refresh': NO_NETWORK,
    });

    await startApp();

    await expectTheCheckFailed(CHECK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T3
  it('opens signed in on Try again once the network is back, without asking for the password', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({ 'GET /users/me': NO_NETWORK });
    await startApp();
    await expectTheCheckFailed(NETWORK_MESSAGE);

    server.answer({ 'GET /users/me': ME });
    await userEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Signed in as Lucía Citizen')).toBeOnTheScreen();
    expect(server.count('POST /sessions')).toBe(0);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T3: the token expired while the app was closed; Try again refreshes the saved session.
  it('refreshes the saved session on Try again once the network is back', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({
      'GET /users/me': () => json(401, { status: 401 }),
      'POST /sessions/refresh': NO_NETWORK,
    });
    await startApp();
    await expectTheCheckFailed(CHECK_MESSAGE);

    server.answer({
      'GET /users/me': (request) =>
        request.headers.get('Authorization') === 'Bearer refreshed-access'
          ? json(200, CITIZEN)
          : json(401, { status: 401 }),
      'POST /sessions/refresh': () => json(200, REFRESHED),
    });
    await userEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Signed in as Lucía Citizen')).toBeOnTheScreen();
    expect(server.count('POST /sessions')).toBe(0);
    expect(savedTokens()).toEqual(REFRESHED);
  });

  // T4
  it('offers to try again when the API answers 503, and keeps the tokens', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({ 'GET /users/me': () => json(503, { status: 503 }) });

    await startApp();

    await expectTheCheckFailed(CHECK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T4: the refresh of an expired token answers 503.
  it('keeps the session when the refresh answers 503', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({
      'GET /users/me': () => json(401, { status: 401 }),
      'POST /sessions/refresh': () => json(503, { status: 503 }),
    });

    await startApp();

    await expectTheCheckFailed(CHECK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T5
  it('stops waiting after the time limit when the API never answers', async () => {
    jest.useFakeTimers();
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({ 'GET /users/me': NEVER });

    await startApp();

    await act(() => jest.advanceTimersByTimeAsync(SESSION_CHECK_TIMEOUT_MS - 1));
    expect(screen.getByLabelText('Loading')).toBeOnTheScreen();

    await act(() => jest.advanceTimersByTimeAsync(1));
    await expectTheCheckFailed(NETWORK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T6
  it('shows the sign-in screen with the expired-session message when the refresh is rejected', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({
      'GET /users/me': () => json(401, { status: 401 }),
      'POST /sessions/refresh': () =>
        json(401, { status: 401, code: 'INVALID_REFRESH_TOKEN', title: 'Unauthorized' }),
    });

    await startApp();

    await expectTheSignInScreen();
    expect(screen.getByText(EXPIRED_MESSAGE)).toBeOnTheScreen();
    expect(savedTokens()).toBeNull();
  });

  // T7
  it('shows the sign-in screen without a saved session, even without network', async () => {
    const server = api({ 'GET /users/me': NO_NETWORK });

    await startApp();

    await expectTheSignInScreen();
    expect(screen.queryByText(EXPIRED_MESSAGE)).not.toBeOnTheScreen();
    expect(server.requests).toEqual([]);
  });

  // T8
  it('discards a saved session the secure store cannot decrypt', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    jest
      .mocked(SecureStore.getItemAsync)
      .mockRejectedValueOnce(new Error('Could not decrypt the value'));
    const server = api({ 'GET /users/me': ME });

    await startApp();

    await expectTheSignInScreen();
    expect(savedTokens()).toBeNull();
    expect(server.requests).toEqual([]);
  });

  // T8: a saved value that is not a session.
  it('discards a damaged saved session', async () => {
    items.set(TOKENS_KEY, '{not json');
    const server = api({ 'GET /users/me': ME });

    await startApp();

    await expectTheSignInScreen();
    expect(items.has(TOKENS_KEY)).toBe(false);
    expect(server.requests).toEqual([]);
  });

  // T9
  it('keeps the message and the tokens when Try again still has no network', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({ 'GET /users/me': NO_NETWORK });
    await startApp();
    await expectTheCheckFailed(NETWORK_MESSAGE);

    await userEvent.press(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(server.count('GET /users/me')).toBe(2));
    await expectTheCheckFailed(NETWORK_MESSAGE);
    expect(savedTokens()).toEqual(SAVED);
  });

  // T9: repeated taps while a check runs send one request at a time.
  it('sends one check at a time however many times Try again is tapped', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({ 'GET /users/me': NO_NETWORK });
    await startApp();
    await expectTheCheckFailed(NETWORK_MESSAGE);

    let answer: (response: Response) => void = () => undefined;
    server.answer({
      'GET /users/me': () =>
        new Promise<Response>((resolve) => {
          answer = resolve;
        }),
    });
    await userEvent.press(screen.getByRole('button', { name: 'Try again' }));
    const trying = await screen.findByRole('button', { name: 'Trying again…' });
    expect(trying).toBeDisabled();
    await userEvent.press(trying);
    await userEvent.press(trying);
    expect(server.count('GET /users/me')).toBe(2);

    answer(json(200, CITIZEN));

    expect(await screen.findByText('Signed in as Lucía Citizen')).toBeOnTheScreen();
    expect(server.count('GET /users/me')).toBe(2);
  });
});
