import type { ApiFailure, ApiResult } from '@urbanpulse/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react-native';
import type { ReactElement } from 'react';

import { createQueryClient } from './query-client';

/** Renders with a cache of its own, so no test sees the data of another; retries do not wait. */
export async function renderWithQueries(ui: ReactElement) {
  const queryClient = createQueryClient({ retryDelay: 0 });
  // An infinite gcTime schedules no garbage collection timer, which would outlive the test.
  queryClient.setDefaultOptions({
    queries: { ...queryClient.getDefaultOptions().queries, gcTime: Infinity },
  });
  await render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
  return queryClient;
}

export function ok<T>(data: T): ApiResult<T> {
  return { ok: true, data };
}

export function failed<T>(failure: ApiFailure): ApiResult<T> {
  return { ok: false, failure };
}
