import { getIncident, listIncidents, listIncidentStatusChanges } from '@urbanpulse/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';

/** Rows per page of the inbox; the API allows up to 100 (docs/api.md, "Listings"). */
export const INBOX_PAGE_SIZE = 20;

const incidentKeys = {
  page: (page: number) => ['incidents', 'page', page] as const,
  detail: (incidentId: string) => ['incidents', incidentId] as const,
  history: (incidentId: string) => ['incidents', incidentId, 'status-changes'] as const,
};

/**
 * One page of the incidents the user may see, newest first (the API's default order, with `id`
 * as the tie-break, so no row appears on two pages).
 *
 * @param page starting at 0, as in the API
 */
export function useIncidentPage(page: number) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.page(page),
    queryFn: () => dataOf(listIncidents(api, { page, size: INBOX_PAGE_SIZE })),
    // The current rows stay on screen while the next page loads, so the table does not jump.
    placeholderData: keepPreviousData,
  });
}

export function useIncident(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.detail(incidentId),
    queryFn: () => dataOf(getIncident(api, incidentId)),
  });
}

export function useIncidentHistory(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.history(incidentId),
    queryFn: () => dataOf(listIncidentStatusChanges(api, incidentId)),
  });
}
