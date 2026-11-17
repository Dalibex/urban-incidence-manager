import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';
import { QueryClient } from '@tanstack/react-query';

/** A failed API call inside a query, so React Query sees it as an error and keeps its `failure`. */
export class ApiCallError extends Error {
  constructor(readonly failure: ApiFailure) {
    super(failure.kind === 'network' ? 'API not reachable' : `API answered ${failure.status}`);
  }
}

/** The data of a successful call; a failed one throws an {@link ApiCallError}. */
export async function dataOf<T>(call: Promise<ApiResult<T>>): Promise<T> {
  const result = await call;
  if (!result.ok) {
    throw new ApiCallError(result.failure);
  }
  return result.data;
}

const MAX_RETRIES = 2;

interface QueryClientOptions {
  /** Wait between retries, in ms. Tests set 0; by default it grows with each attempt. */
  retryDelay?: number;
}

/**
 * Server state of the web app (ADR-0006). Only failures that may pass on their own are retried: the
 * API not reachable or a `5xx`. A `4xx` gives the same answer every time.
 *
 * Queries and mutations run even while the browser says it is offline (#276). By default React
 * Query pauses them instead: a read stays on its loading state with no way to retry, and a change
 * stays on "Saving…" and is sent by itself when the network comes back, even an action the user
 * gave up on, such as a rejection, which is final. Run anyway, they fail at once, so the screen
 * says that the server cannot be reached and offers to try again.
 */
export function createQueryClient({ retryDelay }: QueryClientOptions = {}): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => failureCount < MAX_RETRIES && isTransient(error),
        ...(retryDelay === undefined ? {} : { retryDelay }),
        networkMode: 'always',
      },
      mutations: {
        networkMode: 'always',
      },
    },
  });
}

function isTransient(error: unknown): boolean {
  if (!(error instanceof ApiCallError)) {
    return false;
  }
  return error.failure.kind === 'network' || error.failure.status >= 500;
}
