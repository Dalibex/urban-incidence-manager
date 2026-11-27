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
// Times the session of a store ended for the requests marked before (#259): a sign-out starts, for
// those sent before it; it finishes, for those sent while it waited for the server; or a rejected
// refresh ends the session. It only grows, so a mark compares it and nothing else.
const sessionEnds = new WeakMap<TokenStore, number>();

function signOutCount(tokenStore: TokenStore): number {
  return signOuts.get(tokenStore) ?? 0;
}

function isSigningOut(tokenStore: TokenStore): boolean {
  return (signOutsInProgress.get(tokenStore) ?? 0) > 0;
}

function sessionEndCount(tokenStore: TokenStore): number {
  return sessionEnds.get(tokenStore) ?? 0;
}

function endSessionOfMarks(tokenStore: TokenStore): void {
  sessionEnds.set(tokenStore, sessionEndCount(tokenStore) + 1);
}

/**
 * Called by `signOut` before it revokes the session. A refresh may still save its tokens, so that
 * the revocation can use them, but a rejected one is not reported as an expired session.
 */
export function startSignOut(tokenStore: TokenStore): void {
  signOutsInProgress.set(tokenStore, (signOutsInProgress.get(tokenStore) ?? 0) + 1);
  endSessionOfMarks(tokenStore);
}

/**
 * Called by `signOut` right before it clears the store. From then on, no refresh that started
 * earlier saves its tokens, clears the store or reports an expired session (#237).
 */
export function finishSignOut(tokenStore: TokenStore): void {
  signOutsInProgress.set(tokenStore, (signOutsInProgress.get(tokenStore) ?? 1) - 1);
  signOuts.set(tokenStore, signOutCount(tokenStore) + 1);
  endSessionOfMarks(tokenStore);
}

/** The session a store holds at one moment, from {@link markSession}. */
export interface SessionMark {
  readonly tokenStore: TokenStore;
  readonly sessionEnds: number;
}

/**
 * Marks the session the store holds now. A request is marked before it reads the tokens, and is
 * only repeated with the session of its mark: once that session has ended, it is not repeated with
 * whatever the store holds by then, another user's session or the refreshed tokens of the session
 * that ended (#259). A request sent while a sign-out waits for the server belongs to the session
 * being signed out, so its session ends when the sign-out finishes. The `DELETE` of `signOut` has
 * finished by then, so it is still repeated with a fresh token to revoke the session. Until then,
 * only that `DELETE` refreshes: any other request is answered as the server answered it (#373).
 */
export function markSession(tokenStore: TokenStore): SessionMark {
  return { tokenStore, sessionEnds: sessionEndCount(tokenStore) };
}

/** Whether the session of the mark has ended: by a sign-out, or by a rejected refresh. */
export function hasSessionEnded(mark: SessionMark): boolean {
  return sessionEndCount(mark.tokenStore) !== mark.sessionEnds;
}

const REFRESH_PATH = '/sessions/refresh';

/**
 * How long a request waits for the API to start answering (#276). Without a limit, a server that
 * accepts the connection and never answers, such as a paused container, leaves the screen on
 * "Sending…" for good: React Native sets no read timeout on Android. Only the wait for the answer
 * is limited, not the reading of its body, so a photo that keeps arriving on a slow network is not
 * cut. A refresh after a `401` and the repeated request each have their own limit.
 */
export const RESPONSE_TIMEOUT_MS = 30_000;

/**
 * Sends the request and rejects as a network failure, a `TypeError` like the browser's, when the API
 * has not answered within {@link RESPONSE_TIMEOUT_MS}. The request is aborted too, but the limit does
 * not count on it: a runtime that ignores the signal still ends here. An abort asked by the caller
 * still reaches `fetch`.
 */
function sendWithinTime(
  send: (request: Request) => Promise<Response>,
  request: Request,
): Promise<Response> {
  const controller = new AbortController();
  const callerSignal = request.signal;
  const abortWithCaller = () => controller.abort(callerSignal.reason);
  if (callerSignal.aborted) {
    abortWithCaller();
  } else {
    callerSignal.addEventListener('abort', abortWithCaller, { once: true });
  }
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      reject(new TypeError(`The API did not answer within ${RESPONSE_TIMEOUT_MS} ms`));
      controller.abort();
    }, RESPONSE_TIMEOUT_MS);
  });
  // Started inside the chain, so a `send` that throws at once still clears the timer and listener.
  const answer = Promise.resolve().then(() =>
    send(new Request(request, { signal: controller.signal })),
  );
  return Promise.race([answer, timeout]).finally(() => {
    clearTimeout(timer);
    callerSignal.removeEventListener('abort', abortWithCaller);
  });
}

// A 401 from these operations means wrong credentials or code, a spent refresh token or an unknown
// challenge, never an expired access token, so it must not trigger a refresh.
const PATHS_WITHOUT_REFRESH = new Set([
  '/sessions',
  REFRESH_PATH,
  '/registrations',
  '/mfa-verifications',
  '/mfa-enrollments',
]);

/**
 * Wraps `fetch` so every request carries the access token and, when the API answers 401, the
 * session is refreshed once and the request repeated once. Concurrent requests share one refresh:
 * the refresh token works only once, so a second refresh would end the session.
 */
export function createSessionFetch(baseUrl: string, options: SessionOptions) {
  const { tokenStore, onSessionExpired } = options;
  const send = (request: Request) =>
    sendWithinTime((limited) => (options.fetch ?? globalThis.fetch)(limited), request);
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

  function isSignOutRequest(request: Request): boolean {
    return request.method === 'DELETE' && operationPath(request) === '/sessions/current';
  }

  async function endSession(signOutsAtStart: number): Promise<null> {
    if (signOutCount(tokenStore) !== signOutsAtStart) {
      // The user signed out first: the store is no longer this session's, and nothing expired.
      return null;
    }
    // Before clearing: no request of this session is repeated with the tokens of the next sign-in.
    endSessionOfMarks(tokenStore);
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
      hasSessionEnded(session) ||
      // While signing out the server may already have revoked the refresh token, and presenting
      // it again is taken as reuse and ends every session (#125); `/push-tokens` answers 401 for
      // a revoked session. Only the DELETE of the sign-out itself may still refresh first.
      (isSigningOut(tokenStore) && !isSignOutRequest(request))
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
