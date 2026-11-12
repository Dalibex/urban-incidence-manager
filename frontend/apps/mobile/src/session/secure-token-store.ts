import type { SessionTokens, TokenStore } from '@urbanpulse/api-client';
import * as SecureStore from 'expo-secure-store';

const TOKENS_KEY = 'urbanpulse.session';

// The operation that started last, settled either way, so a failed one does not stop the next.
let lastOperation: Promise<unknown> = Promise.resolve();

/**
 * Runs each operation after the previous one has finished. `api-client` calls `save` before
 * `clear` when a refresh answers during a sign-out (#237), but nothing in `expo-secure-store` says
 * the native side runs its calls in that order: if the delete ran first, the tokens would survive
 * the sign-out and the next start would be signed in.
 */
function inCallOrder<T>(operation: () => Promise<T>): Promise<T> {
  const result = lastOperation.then(operation);
  lastOperation = result.catch(() => undefined);
  return result;
}

/**
 * Keeps the tokens in the iOS Keychain or the Android Keystore through `expo-secure-store`, never
 * in plain AsyncStorage, so the session survives a restart without leaving them readable.
 */
export const secureTokenStore: TokenStore = {
  load: () =>
    inCallOrder(async () => {
      const saved = await SecureStore.getItemAsync(TOKENS_KEY);
      if (!saved) {
        return null;
      }
      try {
        return JSON.parse(saved) as SessionTokens;
      } catch {
        // A damaged value is no session: remove it so the next start-up does not trip on it again.
        await SecureStore.deleteItemAsync(TOKENS_KEY);
        return null;
      }
    }),
  save: (tokens) => inCallOrder(() => SecureStore.setItemAsync(TOKENS_KEY, JSON.stringify(tokens))),
  clear: () => inCallOrder(() => SecureStore.deleteItemAsync(TOKENS_KEY)),
};
