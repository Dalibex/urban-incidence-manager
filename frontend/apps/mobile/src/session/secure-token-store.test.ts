import type { SessionTokens } from '@urbanpulse/api-client';
import * as SecureStore from 'expo-secure-store';

import { secureTokenStore } from './secure-token-store';

// The native side of `expo-secure-store` may finish its calls in any order: this fake finishes each
// write only when the test says so, so a delete called later could finish first.
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

const items = (SecureStore as unknown as { mockItems: Map<string, string> }).mockItems;

const TOKENS: SessionTokens = {
  tokenType: 'Bearer',
  accessToken: 'refreshed-access',
  accessTokenExpiresAt: '2026-11-11T10:30:00Z',
  refreshToken: 'refreshed-refresh',
  refreshTokenExpiresAt: '2026-11-25T10:15:00Z',
};

/** A native write that stores its value only when `finish` is called. */
function slowWrite() {
  let finish: () => void = () => undefined;
  jest.mocked(SecureStore.setItemAsync).mockImplementationOnce(
    (key, value) =>
      new Promise<void>((resolve) => {
        finish = () => {
          items.set(key, value);
          resolve();
        };
      }),
  );
  return { finish: () => finish() };
}

beforeEach(() => {
  items.clear();
  jest.clearAllMocks();
});

describe('secure token store (#237)', () => {
  it('clears after a save that was called first, even if the native save is slow', async () => {
    const write = slowWrite();

    // A refresh saves its tokens while the sign-out is in progress, then the sign-out clears.
    const saving = secureTokenStore.save(TOKENS);
    const clearing = secureTokenStore.clear();
    await Promise.resolve();
    expect(SecureStore.deleteItemAsync).not.toHaveBeenCalled();

    write.finish();
    await Promise.all([saving, clearing]);

    await expect(secureTokenStore.load()).resolves.toBeNull();
  });

  it('runs the next operation after one that failed, and still reports the failure', async () => {
    jest.mocked(SecureStore.setItemAsync).mockRejectedValueOnce(new Error('Keystore unavailable'));

    const saving = secureTokenStore.save(TOKENS);
    const clearing = secureTokenStore.clear();

    await expect(saving).rejects.toThrow('Keystore unavailable');
    await expect(clearing).resolves.toBeUndefined();
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledTimes(1);
  });
});
