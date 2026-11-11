import type { components } from './schema';

/** Tokens of a session, as `createSession` and `refreshSession` return them. */
export type SessionTokens = components['schemas']['SessionResponse'];

/**
 * Where an app keeps the tokens (ADR-0007: platform code behind a small interface). The web keeps
 * them in memory; the mobile app in `expo-secure-store`. Never `localStorage`. An app passes the
 * same object to `createApiClient`, `signOut` and `uploadIncidentAttachment`: that is how a refresh,
 * a rejected request and a photo upload learn of a sign-out.
 */
export interface TokenStore {
  load(): Promise<SessionTokens | null>;
  save(tokens: SessionTokens): Promise<void>;
  clear(): Promise<void>;
}

export interface SessionOptions {
  tokenStore: TokenStore;
  /** Replaces the global `fetch`, for tests. */
  fetch?: (request: Request) => Promise<Response>;
  /**
   * Called when the refresh token is rejected: the user has to sign in again. Never while the user
   * is signing out, nor for a refresh that answers after it.
   */
  onSessionExpired?: () => void;
}

// Sign-outs per store, so a refresh can tell whether the user signed out while it was in flight. A
// count and not a flag: after signing out the user may sign in again before that refresh answers.
const signOuts = new WeakMap<TokenStore, number>();
// Sign-outs that have started and not cleared the store yet; a count, because both can overlap.
const signOutsInProgress = new WeakMap<TokenStore, number>();

function signOutCount(tokenStore: TokenStore): number {
  return signOuts.get(tokenStore) ?? 0;
}

function isSigningOut(tokenStore: TokenStore): boolean {
  return (signOutsInProgress.get(tokenStore) ?? 0) > 0;
}

/**
 * Called by `signOut` before it revokes the session. A refresh may still save its tokens, so that
 * the revocation can use them, but a rejected one is not reported as an expired session.
 */
export function startSignOut(tokenStore: TokenStore): void {
  signOutsInProgress.set(tokenStore, (signOutsInProgress.get(tokenStore) ?? 0) + 1);
}

/**
 * Called by `signOut` right before it clears the store. From then on, no refresh that started
 * earlier saves its tokens, clears the store or reports an expired session (#237).
 */
export function finishSignOut(tokenStore: TokenStore): void {
  signOutsInProgress.set(tokenStore, (signOutsInProgress.get(tokenStore) ?? 1) - 1);
  signOuts.set(tokenStore, signOutCount(tokenStore) + 1);
}

/** Sign-outs started on this store, finished or not: it grows when one starts, never when it ends. */
function signOutsStarted(tokenStore: TokenStore): number {
  return signOutCount(tokenStore) + (signOutsInProgress.get(tokenStore) ?? 0);
}

/** The session a store holds at one moment, from {@link markSession}. */
export interface SessionMark {
  readonly tokenStore: TokenStore;
  readonly signOutsStarted: number;
}

/**
 * Marks the session the store holds now. A request is marked before it reads the tokens, and is
 * only repeated with the session of its mark: once a sign-out has started, it is not repeated with
 * whatever the store holds by then, another user's session or the refreshed tokens of the session
 * that ended (#259). The `DELETE` of `signOut` is marked after `startSignOut`, so it is still
 * repeated with a fresh token to revoke the session.
 */
export function markSession(tokenStore: TokenStore): SessionMark {
  return { tokenStore, signOutsStarted: signOutsStarted(tokenStore) };
}

/** Whether a sign-out has started since the mark was taken. */
export function hasSessionEnded(mark: SessionMark): boolean {
  return signOutsStarted(mark.tokenStore) !== mark.signOutsStarted;
}

const REFRESH_PATH = '/sessions/refresh';

// A 401 from these operations means wrong credentials or a spent refresh token, never an expired
// access token, so it must not trigger a refresh.
const PATHS_WITHOUT_REFRESH = new Set(['/sessions', REFRESH_PATH, '/registrations']);

/**
 * Wraps `fetch` so every request carries the access token and, when the API answers 401, the
 * session is refreshed once and the request repeated once. Concurrent requests share one refresh:
 * the refresh token works only once, so a second refresh would end the session.
 */
export function createSessionFetch(baseUrl: string, options: SessionOptions) {
  const { tokenStore, onSessionExpired } = options;
  const send = (request: Request) => (options.fetch ?? globalThis.fetch)(request);
  // The placeholder origin only lets a relative base URL ("/api/v1") be parsed.
  const basePath = withoutTrailingSlashes(new URL(baseUrl, 'http://placeholder').pathname);
  // The last refresh and the access token it replaced. Requests rejected with that token wait for
  // it instead of starting another one, even if the store has not saved the new tokens yet. A
  // refresh that ended the session stays here too: the store may still answer with the old tokens,
  // and sending them again would look like a stolen token to the server.
  let lastRefresh: { replaces: string; tokens: Promise<SessionTokens | null> } | null = null;

  function operationPath(request: Request): string {
    const { pathname } = new URL(request.url);
    return pathname.startsWith(basePath) ? pathname.slice(basePath.length) : pathname;
  }

  async function endSession(signOutsAtStart: number): Promise<null> {
    if (signOutCount(tokenStore) !== signOutsAtStart) {
      // The user signed out first: the store is no longer this session's, and nothing expired.
      return null;
    }
    await tokenStore.clear();
    // Signing out ends the session anyway: the user asked for it, so it did not expire.
    if (!isSigningOut(tokenStore)) {
      onSessionExpired?.();
    }
    return null;
  }

  /**
   * The refresh said nothing about the token, so the session may still be valid: keep the tokens,
   * let the caller see the original 401, and let the next 401 try again. Requests already waiting
   * for this refresh still share its result.
   */
  function retryAtNextRejection(accessToken: string): null {
    if (lastRefresh?.replaces === accessToken) {
      lastRefresh = null;
    }
    return null;
  }

  async function refresh(
    current: SessionTokens,
    rejectedUrl: string,
    signOutsAtStart: number,
  ): Promise<SessionTokens | null> {
    let response: Response;
    try {
      response = await send(
        // Resolved against the rejected request, which is absolute even when the base URL is not.
        new Request(new URL(`${basePath}${REFRESH_PATH}`, rejectedUrl), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        }),
      );
    } catch {
      return retryAtNextRejection(current.accessToken);
    }
    if (response.status === 401) {
      return endSession(signOutsAtStart);
    }
    if (!response.ok) {
      return retryAtNextRejection(current.accessToken);
    }
    try {
      const tokens = (await response.json()) as SessionTokens;
      // Checked with no await before `save`, so a sign-out cannot slip in between. Saving now would
      // bring back the session the user ended, and the request is not repeated with it either.
      if (signOutCount(tokenStore) !== signOutsAtStart) {
        return null;
      }
      await tokenStore.save(tokens);
      return tokens;
    } catch {
      // The server already rotated the refresh token, so the stored one is spent: without the new
      // tokens the session cannot continue.
      return endSession(signOutsAtStart);
    }
  }

  /** Tokens to repeat a request rejected with `used`, refreshing only if nobody did it yet. */
  function tokensAfterRejection(
    used: SessionTokens,
    rejectedUrl: string,
  ): Promise<SessionTokens | null> {
    if (lastRefresh?.replaces === used.accessToken) {
      return lastRefresh.tokens;
    }
    // Registered before any await, so concurrent rejections always find it.
    const tokens = (async () => {
      // Counted before reading the store: a sign-out during the read must not send a refresh with
      // the tokens it read, which would leave a new session alive on the server.
      const signOutsAtStart = signOutCount(tokenStore);
      const current = await tokenStore.load();
      if (!current || signOutCount(tokenStore) !== signOutsAtStart) {
        return null;
      }
      return current.accessToken === used.accessToken
        ? refresh(current, rejectedUrl, signOutsAtStart)
        : current;
    })();
    lastRefresh = { replaces: used.accessToken, tokens };
    return tokens;
  }

  return async function sessionFetch(request: Request): Promise<Response> {
    // A request body can be read once: keep a copy in case the request has to be repeated.
    const retry = request.clone();
    const session = markSession(tokenStore);
    const tokens = await tokenStore.load();
    const response = await send(withAccessToken(request, tokens));
    if (
      response.status !== 401 ||
      !tokens ||
      PATHS_WITHOUT_REFRESH.has(operationPath(request)) ||
      // A request of a session that has ended neither refreshes nor is repeated (#259).
      hasSessionEnded(session)
    ) {
      return response;
    }
    const fresh = await tokensAfterRejection(tokens, request.url);
    // Checked again, with no await before sending: the user may sign out during the refresh.
    return fresh && !hasSessionEnded(session) ? send(withAccessToken(retry, fresh)) : response;
  };
}

/**
 * openapi-fetch drops a trailing slash too, so "/api/v1/" and "/api/v1" call the same paths. A loop
 * instead of `/\/+$/`, whose backtracking is quadratic on long runs of slashes (CodeQL ReDoS).
 */
export function withoutTrailingSlashes(path: string): string {
  let end = path.length;
  while (end > 0 && path[end - 1] === '/') {
    end -= 1;
  }
  return path.slice(0, end);
}

function withAccessToken(request: Request, tokens: SessionTokens | null): Request {
  if (tokens) {
    request.headers.set('Authorization', `${tokens.tokenType} ${tokens.accessToken}`);
  }
  return request;
}
