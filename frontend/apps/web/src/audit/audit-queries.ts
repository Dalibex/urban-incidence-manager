import { listAuditEntries } from '@urbanpulse/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { toListQuery, type AuditFilters } from './audit-filters';

/** Rows per page of the audit log; the API allows up to 100 (docs/api.md, "Listings"). */
export const AUDIT_PAGE_SIZE = 20;

/**
 * One page of the audit entries that match `filters`, newest first (the API's default order, with
 * `id` as the tie-break, so no row appears on two pages).
 *
 * @param page starting at 0, as in the API
 */
export function useAuditEntryPage(page: number, filters: AuditFilters) {
  const { api } = useSession();
  return useQuery({
    queryKey: ['audit-entries', 'page', page, filters] as const,
    queryFn: () =>
      dataOf(listAuditEntries(api, { page, size: AUDIT_PAGE_SIZE, ...toListQuery(filters) })),
    // The current rows stay on screen while the next page loads, so the table does not jump.
    placeholderData: keepPreviousData,
  });
}
