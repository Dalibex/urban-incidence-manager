import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';

/**
 * Waits before each automatic retry, in ms: three attempts in all. Short, because the citizen is
 * watching the screen; after them the citizen can try again by hand.
 */
export const RETRY_DELAYS_MS: readonly number[] = [1_000, 3_000];

/** Only failures that may pass on their own are retried: no network or a `5xx`. */
export function isTransient(failure: ApiFailure): boolean {
  return failure.kind === 'network' || failure.status >= 500;
}

/**
 * Runs `attempt` until it succeeds, fails with a failure that will not pass on its own, or the
 * retries run out. Repeating an upload is safe: the API stores the same content once.
 */
export async function withRetries<T>(
  attempt: () => Promise<ApiResult<T>>,
  delaysMs: readonly number[] = RETRY_DELAYS_MS,
): Promise<ApiResult<T>> {
  let result = await attempt();
  for (const delay of delaysMs) {
    if (result.ok || !isTransient(result.failure)) {
      return result;
    }
    await new Promise((resolve) => setTimeout(resolve, delay));
    result = await attempt();
  }
  return result;
}
