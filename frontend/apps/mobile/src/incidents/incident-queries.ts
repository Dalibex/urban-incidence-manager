import { getIncident, listIncidents, listIncidentStatusChanges } from '@urbanpulse/api-client';
import { FINAL_STATUSES, OPEN_STATUSES, type IncidentStatus } from '@urbanpulse/domain';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';

/** Reports per page; the API allows up to 100 (docs/api.md, "Listings"). */
export const MY_REPORTS_PAGE_SIZE = 20;

/** The two groups of *My reports*: closed are the final states of ADR-0020, open the rest. */
export type ReportGroup = 'open' | 'closed';

const STATUSES: Record<ReportGroup, readonly IncidentStatus[]> = {
  open: OPEN_STATUSES,
  closed: FINAL_STATUSES,
};

export const incidentKeys = {
  all: ['incidents'] as const,
  myReports: (group: ReportGroup) => ['incidents', 'mine', group] as const,
  detail: (incidentId: string) => ['incidents', incidentId] as const,
  history: (incidentId: string) => ['incidents', incidentId, 'status-changes'] as const,
};

/**
 * The citizen's reports of one group, newest first, one page more each time the list reaches its
 * end. The API scopes `GET /incidents` to the reporter for a citizen (#37), so no user filter is
 * sent.
 */
export function useMyReports(group: ReportGroup) {
  const { api } = useSession();
  return useInfiniteQuery({
    queryKey: incidentKeys.myReports(group),
    queryFn: ({ pageParam }) =>
      dataOf(
        listIncidents(api, {
          page: pageParam,
          size: MY_REPORTS_PAGE_SIZE,
          status: [...STATUSES[group]],
        }),
      ),
    initialPageParam: 0,
    getNextPageParam: ({ page }) =>
      page.number + 1 < page.totalPages ? page.number + 1 : undefined,
  });
}

export function useIncident(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.detail(incidentId),
    queryFn: () => dataOf(getIncident(api, incidentId)),
  });
}

/** The history the citizen may see: the API leaves out the internal entries and reasons. */
export function useIncidentHistory(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.history(incidentId),
    queryFn: () => dataOf(listIncidentStatusChanges(api, incidentId)),
  });
}
