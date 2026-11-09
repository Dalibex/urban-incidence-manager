import {
  createApiClient,
  getCurrentUser,
  signIn as signInOnApi,
  signOut as signOutOnApi,
  signUp as signUpOnApi,
  uploadIncidentAttachment,
  type ApiClient,
  type ApiResult,
  type components,
  type LocalFile,
} from '@urbanpulse/api-client';
import { useQueryClient } from '@tanstack/react-query';
import type { Role } from '@urbanpulse/domain';
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { secureTokenStore } from './secure-token-store';

/** The signed-in user, with the fields of `UserResponse` that the app shows. */
export interface SessionUser {
  id: string;
  name: string;
  email: string;
  role: Role;
}

export interface Credentials {
  email: string;
  password: string;
}

export interface Registration extends Credentials {
  name: string;
}

interface SessionValue {
  /** True while the session saved on the device is being checked at start-up. */
  restoring: boolean;
  user: SessionUser | null;
  /** True after the API rejected the refresh token, until the next sign-in. */
  expired: boolean;
  /** Typed client of the current session; it sends the access token and refreshes on its own. */
  api: ApiClient;
  /** Uploads a photo to an incident with the session's token, reporting the fraction sent. */
  uploadAttachment: (
    incidentId: string,
    photo: LocalFile,
    onProgress: (fraction: number) => void,
  ) => Promise<ApiResult<components['schemas']['AttachmentResponse']>>;
  signIn: (credentials: Credentials) => Promise<ApiResult<void>>;
  signUp: (registration: Registration) => Promise<ApiResult<void>>;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

// Expo inlines EXPO_PUBLIC_* variables at build time; a phone cannot reach the PC as `localhost`.
const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL;
if (!API_BASE_URL) {
  throw new Error('Set EXPO_PUBLIC_API_BASE_URL in apps/mobile/.env (see .env.example)');
}

export function SessionProvider({ children }: { children: ReactNode }) {
  // The cached incidents belong to the user who read them: they go away with the session.
  const queryClient = useQueryClient();
  const [restoring, setRestoring] = useState(true);
  const [user, setUser] = useState<SessionUser | null>(null);
  const [expired, setExpired] = useState(false);
  const [api] = useState(() =>
    createApiClient(API_BASE_URL, {
      tokenStore: secureTokenStore,
      onSessionExpired: () => {
        queryClient.clear();
        setUser(null);
        setExpired(true);
      },
    }),
  );

  useEffect(() => {
    async function restore() {
      try {
        if (await secureTokenStore.load()) {
          // Refreshes on its own if the access token expired while the app was closed. Without
          // network the tokens stay saved, and the next start-up tries again.
          const me = await getCurrentUser(api);
          if (me.ok) {
            setUser(me.data);
          }
        }
      } catch (error) {
        // A session that cannot be read (for example, the Keystore cannot decrypt it after a
        // backup restore) must not block the app: discard it and ask for sign-in.
        console.warn('Discarding the saved session', error);
        await secureTokenStore.clear().catch(() => undefined);
      } finally {
        setRestoring(false);
      }
    }
    void restore();
  }, [api]);

  const value = useMemo<SessionValue>(
    () => ({
      restoring,
      user,
      expired,
      api,
      uploadAttachment: (incidentId, photo, onProgress) =>
        uploadIncidentAttachment(api, incidentId, photo, {
          baseUrl: API_BASE_URL,
          tokenStore: secureTokenStore,
          onProgress,
        }),
      signIn: async (credentials) => {
        const session = await signInOnApi(api, secureTokenStore, credentials);
        if (!session.ok) {
          return session;
        }
        // The role comes from the API, never from decoding the token.
        const me = await getCurrentUser(api);
        if (!me.ok) {
          // Revokes the new session on the server too, not only on this device.
          await signOutOnApi(api, secureTokenStore);
          return me;
        }
        setExpired(false);
        setUser(me.data);
        return { ok: true, data: undefined };
      },
      // Only creates the account: the contract does not sign in, so the screen signs in next and
      // can tell a failed sign-up from a failed sign-in.
      signUp: async (registration) => {
        const account = await signUpOnApi(api, registration);
        return account.ok ? { ok: true, data: undefined } : account;
      },
      signOut: async () => {
        try {
          await signOutOnApi(api, secureTokenStore);
        } finally {
          // The tokens are gone either way. A refresh rejected while signing out is not an expiry.
          queryClient.clear();
          setUser(null);
          setExpired(false);
        }
      },
    }),
    [api, queryClient, restoring, user, expired],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession must be called inside a SessionProvider');
  }
  return session;
}
