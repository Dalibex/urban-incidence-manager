/**
 * Acceptance tests of #236 for the network failures of every `fetch` the clients use (#244): T1,
 * T2, T3, T5, T7 and T8. The screens' side of T1, T2 and T6 is tested in the mobile app.
 */
import { describe, expect, it, vi } from 'vitest';

import {
  createApiClient,
  createIncident,
  getCurrentUser,
  signIn,
  signOut,
  type SessionTokens,
  type TokenStore,
} from './index';
import type { components } from './schema';

const BASE_URL = 'https://api.test/api/v1';

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const REPORT: components['schemas']['CreateIncidentRequest'] = {
  title: 'Street light off',
  description: 'It has been off for three days.',
  location: { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 },
};

/**
 * The error Expo's global `fetch` rejects with when the request does not reach the API, copied from
 * `expo/build/winter/fetch/FetchErrors.js` (Expo 58): `expo/fetch` does not export it. It extends
 * `Error`, not `TypeError`, and keeps the name `Error`.
 */
class ExpoFetchError extends Error {
  constructor(message: string) {
    super(`fetch failed: ${message}`);
  }
}

function memoryStore(initial: SessionTokens | null): TokenStore & { current: () => unknown } {
  let tokens = initial;
  return {
    load: async () => tokens,
    save: async (next) => {
      tokens = next;
    },
    clear: async () => {
      tokens = null;
    },
    current: () => tokens,
  };
}

/** A client whose `fetch` always rejects with `error`. */
function clientFailingWith(error: Error, store = memoryStore(TOKENS)) {
  const fetch = vi.fn(async () => {
    throw error;
  });
  return { client: createApiClient(BASE_URL, { tokenStore: store, fetch }), store };
}

const NETWORK_FAILURES: [string, Error][] = [
  // T1
  ['Expo without network', new ExpoFetchError('The Internet connection appears to be offline.')],
  // T5
  ['Expo after a timeout', new ExpoFetchError('The request timed out.')],
  // T8
  ['the browser', new TypeError('Failed to fetch')],
  ['Node', new TypeError('fetch failed')],
];

describe('network failures (#236)', () => {
  it.each(NETWORK_FAILURES)('reports a network failure from %s', async (_source, error) => {
    const { client } = clientFailingWith(error);

    expect(await getCurrentUser(client)).toEqual({ ok: false, failure: { kind: 'network' } });
  });

  it.each(NETWORK_FAILURES)(
    'reports a form sent without network from %s',
    async (_source, error) => {
      const { client } = clientFailingWith(error);

      const result = await createIncident(client, REPORT);

      expect(result).toEqual({ ok: false, failure: { kind: 'network' }, fieldErrors: [] });
    },
  );

  // T2
  it('reports a sign-in without network as a network failure and stores no tokens', async () => {
    const { client, store } = clientFailingWith(
      new ExpoFetchError('Could not connect to the server.'),
      memoryStore(null),
    );

    const result = await signIn(client, store, { email: 'lucia@example.com', password: 'secret' });

    expect(result).toEqual({ ok: false, failure: { kind: 'network' } });
    expect(store.current()).toBeNull();
  });

  // T3
  it('signs out without network: the tokens are cleared and nothing is thrown', async () => {
    const { client, store } = clientFailingWith(
      new ExpoFetchError('The Internet connection appears to be offline.'),
    );

    await expect(signOut(client, store)).resolves.toBeUndefined();

    expect(store.current()).toBeNull();
  });

  // T7: an error without the prefix, such as Expo's `Unsupported FormData implementation`, is a bug.
  // Expo's `TypeError('Unsupported BodyInit type')` would still count as the network, as in the
  // browser; no call of api-client sends such a body.
  describe('an error that is not a network failure', () => {
    const bug = new Error('Unsupported FormData implementation');

    it('is thrown by a call', async () => {
      const { client } = clientFailingWith(bug);

      await expect(getCurrentUser(client)).rejects.toBe(bug);
    });

    it('is thrown by a form', async () => {
      const { client } = clientFailingWith(bug);

      await expect(createIncident(client, REPORT)).rejects.toBe(bug);
    });

    it('is thrown by the sign-out, which still clears the tokens', async () => {
      const { client, store } = clientFailingWith(bug);

      await expect(signOut(client, store)).rejects.toBe(bug);

      expect(store.current()).toBeNull();
    });
  });
});
