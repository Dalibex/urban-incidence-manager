import {
  createApiClient,
  deletePushToken,
  getCurrentUser,
  getNotificationSettings,
  markSession as markStoreSession,
  registerPushToken,
  signIn as signInOnApi,
  signOut as signOutOnApi,
  signUp as signUpOnApi,
  uploadIncidentAttachment,
  type ApiClient,
  type ApiFailure,
  type ApiResult,
  type components,
  type LocalFile,
  type SessionMark,
  type SessionTokens,
} from '@urbanpulse/api-client';
import { useQueryClient } from '@tanstack/react-query';
import type { Role } from '@urbanpulse/domain';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

import { readPushPermission, readPushToken } from '../notifications/push';
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

/**
 * The check of the session saved on the device at start-up. `failed` means it could not be checked
 * (no network, a server error, no answer in time): the tokens stay. Only an ended session (the API
 * rejected the refresh token, or the new tokens could not be saved) or an unreadable one is gone.
 */
export type RestoreState =
  | { status: 'checking' }
  | { status: 'failed'; failure: ApiFailure; retrying: boolean }
  | { status: 'done' };

interface SessionValue {
  restore: RestoreState;
  /** Checks the saved session again after a failed check; ignored while a check is running. */
  retryRestore: () => void;
  user: SessionUser | null;
  /** True after the API rejected the refresh token, until the next sign-in. */
  expired: boolean;
  /** Typed client of the current session; it sends the access token and refreshes on its own. */
  api: ApiClient;
  /**
   * Uploads a photo to an incident with the session's token, reporting the fraction sent. Every
   * attempt of one upload passes the `session` of the first, so none is sent after a sign-out.
   */
  uploadAttachment: (
    incidentId: string,
    photo: LocalFile,
    onProgress: (fraction: number) => void,
    session?: SessionMark,
  ) => Promise<ApiResult<components['schemas']['AttachmentResponse']>>;
  /** Marks the session the phone holds now, for {@link SessionValue.uploadAttachment} (#259). */
  markSession: () => SessionMark;
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

/**
 * How long the start-up check waits for the API. `fetch` has no time limit of its own, and a server
 * whose database is down answers the refresh only after 30 s.
 */
export const SESSION_CHECK_TIMEOUT_MS = 10_000;

const DONE: RestoreState = { status: 'done' };

type CheckOutcome =
  | { kind: 'signedIn'; user: SessionUser }
  | { kind: 'signedOut' }
  | { kind: 'unverified'; failure: ApiFailure };

/** The saved tokens, or `null` if there are none or they cannot be read. */
async function readSavedTokens(): Promise<SessionTokens | null> {
  try {
    return await secureTokenStore.load();
  } catch (error) {
    // A session that cannot be read (for example, the Keystore cannot decrypt it after a backup
    // restore) must not block the app: discard it and ask for sign-in.
    console.warn('Discarding the saved session', error);
    await secureTokenStore.clear().catch(() => undefined);
    return null;
  }
}

async function checkSavedSession(api: ApiClient): Promise<CheckOutcome> {
  if (!(await readSavedTokens())) {
    return { kind: 'signedOut' };
  }
  let me: Awaited<ReturnType<typeof getCurrentUser>>;
  try {
    // Refreshes on its own if the access token expired while the app was closed.
    me = await getCurrentUser(api);
  } catch (error) {
    // Only an unreadable store discards a session. Anything else thrown here, such as a bug, says
    // nothing about the session, which may still be valid: keep it.
    console.warn('The saved session could not be checked', error);
    return { kind: 'unverified', failure: { kind: 'network' } };
  }
  if (me.ok) {
    return { kind: 'signedIn', user: me.data };
  }
  // A 401 does not say the session ended: the refresh may have failed for lack of network. The
  // client clears the tokens exactly when the session ends, so the store says it.
  return (await readSavedTokens())
    ? { kind: 'unverified', failure: me.failure }
    : { kind: 'signedOut' };
}

/** The outcome of `work`, or `late` if it takes longer than `ms`; a late outcome is dropped. */
function withinTime<T>(work: Promise<T>, ms: number, late: T): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<T>((resolve) => {
    timer = setTimeout(() => resolve(late), ms);
  });
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer));
}

/**
 * Keeps the push token of this device in line with the citizen's setting: registered when push is
 * on and the permission is granted; deleted at sign-in when push is off, because the token may be
 * the previous citizen's. It never breaks the session: the app works without push, so every
 * failure is only logged.
 */
async function syncPushToken(api: ApiClient, signingIn: boolean): Promise<void> {
  try {
    const settings = await getNotificationSettings(api);
    if (!settings.ok) {
      return;
    }
    if (settings.data.push) {
      if ((await readPushPermission()) !== 'granted') {
        return;
      }
      const token = await readPushToken();
      if (token) {
        await registerPushToken(api, token);
      }
    } else if (signingIn) {
      const token = await readPushToken();
      if (token) {
        await deletePushToken(api, token);
      }
    }
  } catch (error) {
    console.warn('The push token could not be synchronised', error);
  }
}

/**
 * Deletes the push token of this device after the session ended, with the access token the session
 * had: it stays valid for a few minutes, and the deletion does not check the session (F2). Best
 * effort: a failure leaves the token to the server's own cleanup.
 */
async function deleteDevicePushToken(
  api: ApiClient,
  token: Promise<string | null>,
  accessToken: string | undefined,
): Promise<void> {
  try {
    const deviceToken = await token;
    if (deviceToken && accessToken) {
      await deletePushToken(api, deviceToken, accessToken);
    }
  } catch (error) {
    console.warn('The push token could not be deleted', error);
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  // The cached incidents belong to the user who read them: they go away with the session.
  const queryClient = useQueryClient();
  const [restore, setRestore] = useState<RestoreState>({ status: 'checking' });
  const [user, setUser] = useState<SessionUser | null>(null);
  const [expired, setExpired] = useState(false);
  // A ref, not state: two taps in the same render must not start two checks.
  const checking = useRef(false);
  const [api] = useState(() =>
    createApiClient(API_BASE_URL, {
      tokenStore: secureTokenStore,
      onSessionExpired: () => {
        queryClient.clear();
        setUser(null);
        setExpired(true);
        // Also when the refresh of a check that ran out of time is rejected later.
        setRestore(DONE);
      },
    }),
  );

  const checkSession = useCallback(async () => {
    if (checking.current) {
      return;
    }
    checking.current = true;
    try {
      const outcome = await withinTime(checkSavedSession(api), SESSION_CHECK_TIMEOUT_MS, {
        kind: 'unverified',
        failure: { kind: 'network' },
      } satisfies CheckOutcome);
      switch (outcome.kind) {
        case 'signedIn':
          setUser(outcome.user);
          setRestore(DONE);
          void syncPushToken(api, false);
          break;
        case 'signedOut':
          setRestore(DONE);
          break;
        case 'unverified':
          setRestore({ status: 'failed', failure: outcome.failure, retrying: false });
          break;
      }
    } finally {
      checking.current = false;
    }
  }, [api]);

  useEffect(() => {
    void checkSession();
  }, [checkSession]);

  const retryRestore = useCallback(() => {
    if (checking.current) {
      return;
    }
    setRestore((current) =>
      current.status === 'failed' ? { ...current, retrying: true } : current,
    );
    void checkSession();
  }, [checkSession]);

  const value = useMemo<SessionValue>(
    () => ({
      restore,
      retryRestore,
      user,
      expired,
      api,
      uploadAttachment: (incidentId, photo, onProgress, session) =>
        uploadIncidentAttachment(api, incidentId, photo, {
          baseUrl: API_BASE_URL,
          tokenStore: secureTokenStore,
          onProgress,
          session,
        }),
      markSession: () => markStoreSession(secureTokenStore),
      signIn: async (credentials) => {
        const session = await signInOnApi(api, secureTokenStore, credentials);
        if (!session.ok) {
          return session;
        }
        if (session.data) {
          // A second-factor challenge: staff sign in on the web (ADR-0021), nothing to store here.
          return { ok: false, failure: { kind: 'problem', status: 202, code: undefined } };
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
        void syncPushToken(api, true);
        return { ok: true, data: undefined };
      },
      // Only creates the account: the contract does not sign in, so the screen signs in next and
      // can tell a failed sign-up from a failed sign-in.
      signUp: async (registration) => {
        const account = await signUpOnApi(api, registration);
        return account.ok ? { ok: true, data: undefined } : account;
      },
      signOut: async () => {
        // Read before signing out, which discards both; neither read may stop the sign-out.
        const deviceToken = readPushToken().catch(() => null);
        let accessToken = await secureTokenStore
          .load()
          .then((tokens) => tokens?.accessToken)
          .catch(() => undefined);
        try {
          // If this throws a non-network error, the device token is not deleted (best effort).
          // A refresh during the sign-out may have replaced the access token that revokes it.
          await signOutOnApi(api, secureTokenStore, (tokens) => {
            accessToken = tokens?.accessToken ?? accessToken;
          });
        } finally {
          // The tokens are gone either way. A refresh rejected while signing out is not an expiry.
          queryClient.clear();
          setUser(null);
          setExpired(false);
        }
        await deleteDevicePushToken(api, deviceToken, accessToken);
      },
    }),
    [api, queryClient, restore, retryRestore, user, expired],
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
