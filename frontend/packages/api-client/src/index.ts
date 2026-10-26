import createClient from 'openapi-fetch';

import type { paths } from './schema';

// schema.d.ts is generated from docs/api/openapi.yml with `pnpm generate:api` and committed.
// Never edit it by hand: the `contract` CI job fails when it differs from the contract.
export type { components, paths } from './schema';

/**
 * Creates a client whose paths, parameters and responses are typed from the contract. Each app
 * passes the base URL from its own configuration, never a hard-coded host.
 */
export function createApiClient(baseUrl: string) {
  return createClient<paths>({ baseUrl });
}

export type ApiClient = ReturnType<typeof createApiClient>;
