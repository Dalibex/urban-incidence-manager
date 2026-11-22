import type { ApiClient } from './index';
import type { components } from './schema';
import { finishSignOut, startSignOut, type TokenStore } from './session';

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
    if (isNetworkFailure(error)) {
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

/** Expo's `FetchError` (`expo/build/winter/fetch/FetchErrors.js`) starts every message with it. */
const EXPO_FETCH_FAILED = 'fetch failed: ';

/**
 * Whether `fetch` rejected because the request did not reach the API; anything else is a bug.
 * The browser and Node reject with a `TypeError`. Expo replaces the global `fetch` on the phone and
 * rejects with its own `FetchError`, which extends `Error` and keeps the name `Error`; `expo/fetch`
 * does not export the class, so only its message tells it apart (#244).
 */
export function isNetworkFailure(error: unknown): boolean {
  return (
    error instanceof TypeError ||
    (error instanceof Error && error.message.startsWith(EXPO_FETCH_FAILED))
  );
}

export function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null && 'status' in value;
}

/**
 * The first step of a sign-in that needs the second factor (ADR-0021). The client keeps it in
 * memory only, never in storage, and gives it to `enrollMfa` and `verifyMfa`.
 */
export type MfaChallenge = components['schemas']['MfaChallengeResponse'];

/**
 * Starts a session and stores its tokens. A staff account answers `202` instead: nothing is stored,
 * and `data` is the challenge the screen answers with `verifyMfa`. `data` is `null` when the user
 * is signed in.
 */
export async function signIn(
  client: ApiClient,
  tokenStore: TokenStore,
  credentials: components['schemas']['CreateSessionRequest'],
): Promise<ApiResult<MfaChallenge | null>> {
  const result = await callApi(() => client.POST('/sessions', { body: credentials }));
  if (!result.ok) {
    return result;
  }
  if (!('accessToken' in result.data)) {
    return { ok: true, data: result.data };
  }
  await tokenStore.save(result.data);
  return { ok: true, data: null };
}

/**
 * Starts the enrolment of the second factor for a challenge with `enrollmentRequired`: the secret
 * and the `otpauth` URI to show as a QR code. Calling it again replaces the secret; `409` with
 * `MFA_ALREADY_ENROLLED` when the account has one, `401` with `INVALID_MFA_CHALLENGE` when the
 * challenge is unknown, expired or used.
 */
export function enrollMfa(
  client: ApiClient,
  challengeToken: string,
): Promise<ApiResult<components['schemas']['MfaEnrollmentResponse']>> {
  return callApi(() => client.POST('/mfa-enrollments', { body: { challengeToken } }));
}

/**
 * Answers a challenge with the code of the authenticator app and stores the tokens. `401` with
 * `INVALID_MFA_CODE` means the user types another code; `401` with `INVALID_MFA_CHALLENGE`, that
 * the sign-in starts again with the password, and so does `403` with `ACCOUNT_DISABLED`. `429` with
 * `TOO_MANY_ATTEMPTS` does not spend the challenge: the user waits and tries again.
 */
export async function verifyMfa(
  client: ApiClient,
  tokenStore: TokenStore,
  challengeToken: string,
  code: string,
): Promise<ApiResult<void>> {
  const result = await callApi(() =>
    client.POST('/mfa-verifications', { body: { challengeToken, code } }),
  );
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

/**
 * Replaces the password with the current one as the credential. It does not sign in: the contract
 * asks for `signIn` with the new password afterwards.
 */
export function changePassword(
  client: ApiClient,
  change: components['schemas']['CreatePasswordChangeRequest'],
): Promise<ApiResult<void>> {
  return callApi(() => client.POST('/password-changes', { body: change }));
}

export function getCurrentUser(
  client: ApiClient,
): Promise<ApiResult<components['schemas']['UserResponse']>> {
  return callApi(() => client.GET('/users/me'));
}

/**
 * Revokes the session on the server and always discards the tokens, even when the server cannot
 * be reached: the user asked to sign out, and the device must not keep a usable session. A refresh
 * still in flight when the tokens are discarded does not bring them back (#237); one that answers
 * before, while `DELETE` waits for it, gives the fresh token that revokes the session. A refresh
 * that sign-out could not use to revoke the session (it answers later, or the `DELETE` itself
 * fails) leaves that session alive on the server until its refresh token expires.
 */
export async function signOut(client: ApiClient, tokenStore: TokenStore): Promise<void> {
  startSignOut(tokenStore);
  try {
    await client.DELETE('/sessions/current');
  } catch (error) {
    if (!isNetworkFailure(error)) {
      throw error;
    }
  } finally {
    // Before clearing, with no await in between, so no late refresh calls `save` after `clear`.
    finishSignOut(tokenStore);
    await tokenStore.clear();
  }
}
