import type { SessionTokens, TokenStore } from '@urbanpulse/api-client';
import * as SecureStore from 'expo-secure-store';

const TOKENS_KEY = 'urbanpulse.session';

/**
 * Keeps the tokens in the iOS Keychain or the Android Keystore through `expo-secure-store`, never
 * in plain AsyncStorage, so the session survives a restart without leaving them readable.
 */
export const secureTokenStore: TokenStore = {
  async load() {
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
  },
  async save(tokens) {
    await SecureStore.setItemAsync(TOKENS_KEY, JSON.stringify(tokens));
  },
  async clear() {
    await SecureStore.deleteItemAsync(TOKENS_KEY);
  },
};
