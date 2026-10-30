import type { components } from './schema';

/** Tokens of a session, as `createSession` and `refreshSession` return them. */
export type SessionTokens = components['schemas']['SessionResponse'];

/**
 * Where an app keeps the tokens (ADR-0007: platform code behind a small interface). The web keeps
 * them in memory; the mobile app in `expo-secure-store`. Never `localStorage`.
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
  /** Called when the refresh token is rejected: the user has to sign in again. */
  onSessionExpired?: () => void;
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
  // openapi-fetch drops a trailing slash too, so "/api/v1/" and "/api/v1" call the same paths.
  const base = baseUrl.replace(/\/+$/, '');
  // The placeholder origin only lets a relative base URL ("/api/v1") be parsed.
  const basePath = new URL(base, 'http://placeholder').pathname;
  // The last refresh and the access token it replaced. Requests rejected with that token wait for
  // it instead of starting another one, even if the store has not saved the new tokens yet.
  let lastRefresh: { replaces: string; tokens: Promise<SessionTokens | null> } | null = null;

  function operationPath(request: Request): string {
    const { pathname } = new URL(request.url);
    return pathname.startsWith(basePath) ? pathname.slice(basePath.length) : pathname;
  }

  async function endSession(): Promise<null> {
    await tokenStore.clear();
    onSessionExpired?.();
    return null;
  }

  async function refresh(refreshToken: string, rejectedUrl: string): Promise<SessionTokens | null> {
    let response: Response;
    try {
      response = await send(
        // Resolved against the rejected request, which is absolute even when the base URL is not.
        new Request(new URL(`${basePath}${REFRESH_PATH}`, rejectedUrl), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        }),
      );
    } catch {
      // Without network the session may still be valid: keep the tokens and let the caller see
      // the original 401.
      return null;
    }
    if (response.status === 401) {
      return endSession();
    }
    if (!response.ok) {
      return null;
    }
    try {
      const tokens = (await response.json()) as SessionTokens;
      await tokenStore.save(tokens);
      return tokens;
    } catch {
      // The server already rotated the refresh token, so the stored one is spent: without the new
      // tokens the session cannot continue.
      return endSession();
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
      const current = await tokenStore.load();
      if (!current) {
        return null;
      }
      return current.accessToken === used.accessToken
        ? refresh(current.refreshToken, rejectedUrl)
        : current;
    })();
    lastRefresh = { replaces: used.accessToken, tokens };
    return tokens;
  }

  return async function sessionFetch(request: Request): Promise<Response> {
    // A request body can be read once: keep a copy in case the request has to be repeated.
    const retry = request.clone();
    const tokens = await tokenStore.load();
    const response = await send(withAccessToken(request, tokens));
    if (response.status !== 401 || !tokens || PATHS_WITHOUT_REFRESH.has(operationPath(request))) {
      return response;
    }
    const fresh = await tokensAfterRejection(tokens, request.url);
    return fresh ? send(withAccessToken(retry, fresh)) : response;
  };
}

function withAccessToken(request: Request, tokens: SessionTokens | null): Request {
  if (tokens) {
    request.headers.set('Authorization', `${tokens.tokenType} ${tokens.accessToken}`);
  }
  return request;
}
