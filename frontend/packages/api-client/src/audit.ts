import type { ApiClient } from './index';
import { callApi, type ApiResult } from './account';
import type { components, operations } from './schema';

/**
 * Query of `listAuditEntries`: `page` starts at 0, `from` is included and `to` excluded; omitted
 * values take the contract's defaults.
 */
export type AuditEntryListQuery = NonNullable<
  operations['listAuditEntries']['parameters']['query']
>;

/** A page of the audit trail, newest first unless `sort` says otherwise. Administrators only. */
export function listAuditEntries(
  client: ApiClient,
  query: AuditEntryListQuery = {},
): Promise<ApiResult<components['schemas']['AuditEntryPageResponse']>> {
  return callApi(() => client.GET('/audit-entries', { params: { query } }));
}
