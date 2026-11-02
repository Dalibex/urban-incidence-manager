import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';
import { focusManager, QueryClient } from '@tanstack/react-query';
import { AppState } from 'react-native';

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

/** Whether a query failed because the API answered `404`: missing, or someone else's incident. */
export function isNotFound(error: unknown): boolean {
  return (
    error instanceof ApiCallError &&
    error.failure.kind === 'problem' &&
    error.failure.status === 404
  );
}

const MAX_RETRIES = 2;

interface QueryClientOptions {
  /** Wait between retries, in ms. Tests set 0; by default it grows with each attempt. */
  retryDelay?: number;
}

/**
 * Server state of the mobile app, configured as on the web. Only failures that may pass on their
 * own are retried: the API not reachable or a `5xx`. A `4xx` gives the same answer every time.
 */
export function createQueryClient({ retryDelay }: QueryClientOptions = {}): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => failureCount < MAX_RETRIES && isTransient(error),
        ...(retryDelay === undefined ? {} : { retryDelay }),
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

/**
 * React Native has no window focus: the app coming back to the foreground counts as one, so the
 * visible queries refetch when they are stale, as React Query documents for React Native.
 */
export function refetchOnAppForeground(): void {
  focusManager.setEventListener((setFocused) => {
    const subscription = AppState.addEventListener('change', (state) => {
      setFocused(state === 'active');
    });
    return () => subscription.remove();
  });
}
