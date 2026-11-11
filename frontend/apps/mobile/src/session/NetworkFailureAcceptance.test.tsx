import { NavigationContainer } from '@react-navigation/native';
import { fireEvent, screen } from '@testing-library/react-native';
// Expo's own class, not a copy: an Expo upgrade that changes its message fails these tests.
import { FetchError } from 'expo/build/winter/fetch/FetchErrors';
import * as SecureStore from 'expo-secure-store';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { RootNavigator } from '../navigation/RootNavigator';
import { renderWithQueries } from '../test-utils';
import { SessionProvider } from './SessionContext';

// Acceptance tests of #236 for the screens (#244): T1, T2 and T6. The app is the real one from the
// session down (provider, API client, query client, navigator and screens); only the network
// (`fetch`), the secure store of the phone and the tabs around *My reports* are replaced. T3, T5,
// T7 and T8 are tested in `api-client`; T4 needs a phone and is a manual case.

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

jest.mock('@expo/vector-icons', () => ({ Ionicons: () => null }));

// The other tabs are not what these cases check: a signed-in citizen sees only *My reports*.
jest.mock('../navigation/MainTabs', () => {
  const { MyReportsStack } = jest.requireActual<typeof import('../navigation/MyReportsStack')>(
    '../navigation/MyReportsStack',
  );
  return { MainTabs: MyReportsStack };
});

const TOKENS_KEY = 'urbanpulse.session';
const items = (SecureStore as unknown as { mockItems: Map<string, string> }).mockItems;

const SAVED = {
  tokenType: 'Bearer',
  accessToken: 'saved-access',
  accessTokenExpiresAt: '2099-11-11T10:15:00Z',
  refreshToken: 'saved-refresh',
  refreshTokenExpiresAt: '2099-11-25T10:00:00Z',
};

const CITIZEN = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Lucía Citizen',
  email: 'lucia@example.com',
  role: 'CITIZEN',
};

const NETWORK_MESSAGE = 'UrbanPulse cannot be reached. Check your connection and try again.';

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
  throw new FetchError('The Internet connection appears to be offline.');
};

const REPORTS: Answer = () =>
  json(200, {
    content: [
      {
        id: 'e1000000-0000-4000-8000-000000000001',
        title: 'Street light off',
        status: 'REPORTED',
        category: null,
        reportedAt: '2026-11-02T09:58:31Z',
        updatedAt: '2026-11-02T09:58:31Z',
      },
    ],
    page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
  });

/** The API: each operation answers with its handler; the requests are kept to count them. */
function api(handlers: Record<string, Answer>) {
  const requests: string[] = [];
  const fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const operation = `${request.method} ${new URL(request.url).pathname}`;
    requests.push(operation);
    const handler = handlers[operation];
    if (!handler) {
      throw new Error(`Unexpected request ${operation}`);
    }
    return handler(request);
  });
  globalThis.fetch = fetch as typeof globalThis.fetch;
  return {
    count: (operation: string) => requests.filter((sent) => sent === operation).length,
    /** Replaces the answers, as when the network comes back. */
    answer: (next: Record<string, Answer>) => Object.assign(handlers, next),
  };
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

const originalFetch = globalThis.fetch;

beforeEach(() => {
  items.clear();
  jest.clearAllMocks();
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe('network failures on the mobile app, acceptance of #236', () => {
  // T1
  it('retries a list without network, says so, and loads it on "Try again"', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    const server = api({ 'GET /users/me': () => json(200, CITIZEN), 'GET /incidents': NO_NETWORK });

    await startApp();

    expect(await screen.findByText(NETWORK_MESSAGE)).toBeOnTheScreen();
    // The first attempt and the two retries of `query-client.ts`.
    expect(server.count('GET /incidents')).toBe(3);

    server.answer({ 'GET /incidents': REPORTS });
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Street light off')).toBeOnTheScreen();
  });

  // T6
  it('says a list could not be loaded when the server fails, not that it cannot be reached', async () => {
    items.set(TOKENS_KEY, JSON.stringify(SAVED));
    api({
      'GET /users/me': () => json(200, CITIZEN),
      'GET /incidents': () => json(503, { status: 503 }),
    });

    await startApp();

    expect(
      await screen.findByText('Your reports could not be loaded. Try again in a moment.'),
    ).toBeOnTheScreen();
    expect(screen.queryByText(NETWORK_MESSAGE)).not.toBeOnTheScreen();
  });

  // T2
  it('says that UrbanPulse cannot be reached when signing in without network', async () => {
    api({ 'POST /sessions': NO_NETWORK });

    await startApp();
    await fireEvent.changeText(await screen.findByLabelText('Email'), 'lucia@example.com');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'secret password');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText(NETWORK_MESSAGE)).toBeOnTheScreen();
    expect(
      screen.queryByText('Something went wrong. Try again in a moment.'),
    ).not.toBeOnTheScreen();
    expect(items.has(TOKENS_KEY)).toBe(false);
  });
});
