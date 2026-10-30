import type { ApiClient } from './index';
import type { components } from './schema';
import type { TokenStore } from './session';

type Problem = components['schemas']['Problem'];

/**
 * Why a call failed. Clients choose their message from `status` and `code` (for example
 * `INVALID_CREDENTIALS`), never from the text of the problem.
 */
export type ApiFailure =
  { kind: 'network' } | { kind: 'problem'; status: number; code: string | undefined };

export type ApiResult<T> = { ok: true; data: T } | { ok: false; failure: ApiFailure };

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Runs a client call and turns its outcome into an `ApiResult`. */
export async function callApi<T>(call: () => Promise<FetchResult<T>>): Promise<ApiResult<T>> {
  let result: FetchResult<T>;
  try {
    result = await call();
  } catch (error) {
    // `fetch` rejects with a TypeError when the API cannot be reached; anything else is a bug.
    if (error instanceof TypeError) {
      return { ok: false, failure: { kind: 'network' } };
    }
    throw error;
  }
  if (result.response.ok) {
    // A 204 has no body: openapi-fetch gives `undefined`, which is the typed `data` of that call.
    return { ok: true, data: result.data as T };
  }
  const code = isProblem(result.error) ? result.error.code : undefined;
  return { ok: false, failure: { kind: 'problem', status: result.response.status, code } };
}

function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null && 'status' in value;
}

/** Starts a session and stores its tokens. */
export async function signIn(
  client: ApiClient,
  tokenStore: TokenStore,
  credentials: components['schemas']['CreateSessionRequest'],
): Promise<ApiResult<void>> {
  const result = await callApi(() => client.POST('/sessions', { body: credentials }));
  if (!result.ok) {
    return result;
  }
  await tokenStore.save(result.data);
  return { ok: true, data: undefined };
}

/** Creates a citizen account. It does not sign in: the contract asks for `signIn` afterwards. */
export function signUp(
  client: ApiClient,
  registration: components['schemas']['CreateRegistrationRequest'],
): Promise<ApiResult<components['schemas']['UserResponse']>> {
  return callApi(() => client.POST('/registrations', { body: registration }));
}

export function getCurrentUser(
  client: ApiClient,
): Promise<ApiResult<components['schemas']['UserResponse']>> {
  return callApi(() => client.GET('/users/me'));
}

/**
 * Revokes the session on the server and always discards the tokens, even when the server cannot
 * be reached: the user asked to sign out, and the device must not keep a usable session.
 */
export async function signOut(client: ApiClient, tokenStore: TokenStore): Promise<void> {
  try {
    await client.DELETE('/sessions/current');
  } catch (error) {
    if (!(error instanceof TypeError)) {
      throw error;
    }
  } finally {
    await tokenStore.clear();
  }
}
