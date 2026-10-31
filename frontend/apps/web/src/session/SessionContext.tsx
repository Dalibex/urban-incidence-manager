import {
  createApiClient,
  getCurrentUser,
  signIn as signInOnApi,
  signOut as signOutOnApi,
  type ApiClient,
  type ApiResult,
  type SessionTokens,
  type TokenStore,
} from '@urbanpulse/api-client';
import type { Role } from '@urbanpulse/domain';
import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

/** The signed-in user, with the fields of `UserResponse` that the shell shows. */
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

interface SessionValue {
  user: SessionUser | null;
  /** True after the API rejected the refresh token, until the next sign-in. */
  expired: boolean;
  api: ApiClient;
  signIn: (credentials: Credentials) => Promise<ApiResult<void>>;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

/**
 * Keeps both tokens in memory only, never in `localStorage` or `sessionStorage`, where any script
 * of the page could read them. The price: reloading the page asks for sign-in again.
 */
function createMemoryTokenStore(): TokenStore {
  let tokens: SessionTokens | null = null;
  return {
    load: async () => tokens,
    save: async (next) => {
      tokens = next;
    },
    clear: async () => {
      tokens = null;
    },
  };
}

interface SessionProviderProps {
  /** Starts the session signed in; tests use it to render a role's screens directly. */
  initialUser?: SessionUser | null;
  /** Replaces the global `fetch`, for tests. */
  fetch?: (request: Request) => Promise<Response>;
  children: ReactNode;
}

export function SessionProvider({ initialUser = null, fetch, children }: SessionProviderProps) {
  const [user, setUser] = useState<SessionUser | null>(initialUser);
  const [expired, setExpired] = useState(false);
  const [{ api, tokenStore }] = useState(() => {
    const store = createMemoryTokenStore();
    const client = createApiClient(API_BASE_URL, {
      tokenStore: store,
      fetch,
      onSessionExpired: () => {
        setUser(null);
        setExpired(true);
      },
    });
    return { api: client, tokenStore: store };
  });

  const value = useMemo<SessionValue>(
    () => ({
      user,
      expired,
      api,
      signIn: async (credentials) => {
        const session = await signInOnApi(api, tokenStore, credentials);
        if (!session.ok) {
          return session;
        }
        // The role comes from the API, never from decoding the token.
        const me = await getCurrentUser(api);
        if (!me.ok) {
          // Revokes the new session on the server too, not only on this device.
          await signOutOnApi(api, tokenStore);
          return me;
        }
        setExpired(false);
        setUser(me.data);
        return { ok: true, data: undefined };
      },
      signOut: async () => {
        try {
          await signOutOnApi(api, tokenStore);
        } finally {
          // The tokens are gone either way. A refresh rejected while signing out is not an expiry.
          setUser(null);
          setExpired(false);
        }
      },
    }),
    [user, expired, api, tokenStore],
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
