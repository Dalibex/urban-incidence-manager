import {
  createApiClient,
  getCurrentUser,
  markSession as markStoreSession,
  signIn as signInOnApi,
  signOut as signOutOnApi,
  verifyMfa as verifyMfaOnApi,
  type ApiClient,
  type ApiFailure,
  type ApiResult,
  type MfaChallenge,
  type SessionMark,
  type SessionTokens,
  type TokenStore,
} from '@urbanpulse/api-client';
import { useQueryClient } from '@tanstack/react-query';
import type { Role } from '@urbanpulse/domain';
import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

/** The signed-in user, with the fields of `UserResponse` that the shell shows. */
export interface SessionUser {
  id: string;
  name: string;
  email: string;
  role: Role;
}

/**
 * The answer to a code. `codeAccepted` marks the one failure after the API accepted it: the
 * challenge is spent and the new session was revoked, so only the password can start again.
 */
export type MfaVerification =
  ApiResult<void> | { ok: false; failure: ApiFailure; codeAccepted: true };

export interface Credentials {
  email: string;
  password: string;
}

interface SessionValue {
  user: SessionUser | null;
  /** True after the API rejected the refresh token, until the next sign-in. */
  expired: boolean;
  api: ApiClient;
  /** `data` is the second-factor challenge of a staff account, or `null` once signed in. */
  signIn: (credentials: Credentials) => Promise<ApiResult<MfaChallenge | null>>;
  /** Answers the challenge of `signIn` with the code of the authenticator app. */
  verifyMfa: (challengeToken: string, code: string) => Promise<MfaVerification>;
  signOut: () => Promise<void>;
  /**
   * Shows a change the API accepted on an account, such as a new name, when that account is the
   * signed-in one: the shell shows the user as they are now, without signing in again.
   */
  showChangedUser: (changed: SessionUser) => void;
  /**
   * Marks the session signed in now, so an answer that arrives later can tell whether that session
   * has ended meanwhile (`hasSessionEnded`) and must not reach the cache of the next one (#287).
   */
  markSession: () => SessionMark;
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
  // The cached incidents belong to the user who read them: they go away with the session.
  const queryClient = useQueryClient();
  const [user, setUser] = useState<SessionUser | null>(initialUser);
  const [expired, setExpired] = useState(false);
  const [{ api, tokenStore }] = useState(() => {
    const store = createMemoryTokenStore();
    const client = createApiClient(API_BASE_URL, {
      tokenStore: store,
      fetch,
      onSessionExpired: () => {
        queryClient.clear();
        setUser(null);
        setExpired(true);
      },
    });
    return { api: client, tokenStore: store };
  });

  const value = useMemo<SessionValue>(() => {
    /** Reads the user of the tokens just stored and shows them signed in. */
    async function openSession(): Promise<ApiResult<void>> {
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
    }
    return {
      user,
      expired,
      api,
      signIn: async (credentials) => {
        const session = await signInOnApi(api, tokenStore, credentials);
        if (!session.ok || session.data) {
          return session;
        }
        const opened = await openSession();
        return opened.ok ? { ok: true, data: null } : opened;
      },
      verifyMfa: async (challengeToken, code) => {
        const session = await verifyMfaOnApi(api, tokenStore, challengeToken, code);
        if (!session.ok) {
          return session;
        }
        const opened = await openSession();
        return opened.ok ? opened : { ...opened, codeAccepted: true };
      },
      signOut: async () => {
        try {
          await signOutOnApi(api, tokenStore);
        } finally {
          // The tokens are gone either way. A refresh rejected while signing out is not an expiry.
          queryClient.clear();
          setUser(null);
          setExpired(false);
        }
      },
      showChangedUser: ({ id, name, email, role }) => {
        // Only the fields the shell shows, never the rest of the account the API answered.
        setUser((current) => (current?.id === id ? { id, name, email, role } : current));
      },
      markSession: () => markStoreSession(tokenStore),
    };
  }, [user, expired, api, tokenStore, queryClient]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession must be called inside a SessionProvider');
  }
  return session;
}
