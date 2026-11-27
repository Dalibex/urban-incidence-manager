import {
  getAttachmentContent,
  getIncident,
  listCategories,
  listIncidentAttachments,
  listIncidents,
  listIncidentStatusChanges,
  type components,
} from '@urbanpulse/api-client';
import { FINAL_STATUSES, OPEN_STATUSES, type IncidentStatus } from '@urbanpulse/domain';
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
  type QueryClient,
  type QueryState,
} from '@tanstack/react-query';

import { blobToDataUri } from '../attachments/blob-uri';
import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';

/** Reports per page; the API allows up to 100 (docs/api.md, "Listings"). */
export const MY_REPORTS_PAGE_SIZE = 20;

type Incident = components['schemas']['IncidentResponse'];

/** The two groups of *My reports*: closed are the final states of ADR-0020, open the rest. */
export type ReportGroup = 'open' | 'closed';

const STATUSES: Record<ReportGroup, readonly IncidentStatus[]> = {
  open: OPEN_STATUSES,
  closed: FINAL_STATUSES,
};

export const categoryKeys = {
  active: ['categories'] as const,
};

export const incidentKeys = {
  all: ['incidents'] as const,
  myReports: (group: ReportGroup) => ['incidents', 'mine', group] as const,
  detail: (incidentId: string) => ['incidents', incidentId] as const,
  history: (incidentId: string) => ['incidents', incidentId, 'status-changes'] as const,
  // Under `all`, so refreshing the incidents refreshes the photos too.
  attachments: (incidentId: string) => ['incidents', incidentId, 'attachments'] as const,
};

export const attachmentKeys = {
  all: ['attachments'] as const,
  content: (attachmentId: string) => ['attachments', attachmentId, 'content'] as const,
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

/** How often a pending address is read again: the backend's worker runs every 30 s (#375). */
export const PENDING_POLL_INTERVAL_MS = 15_000;

/** Reads of a pending address before the screen stops on its own: two minutes in all. */
export const PENDING_POLL_CHECKS = 8;

/**
 * One of the citizen's reports. While its address is `PENDING`, it is read again every 15 s, up to
 * two minutes, so the address the worker resolves after the report replaces *Looking up the
 * address…* (#436); later, a pull to refresh or coming back to the app reads it, and
 * {@link useIncidentDetail} tells the screen that it stopped.
 */
export function useIncident(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.detail(incidentId),
    queryFn: () => dataOf(getIncident(api, incidentId)),
    refetchInterval: (query) => (waitingForAddress(query.state) ? PENDING_POLL_INTERVAL_MS : false),
  });
}

/**
 * The report of its detail screen, and whether the screen stopped reading it on its own while its
 * address is still `PENDING`: after the last check, or after a failed read. The screen then says
 * the address has not arrived yet, instead of a progress it no longer follows (#436).
 */
export function useIncidentDetail(incidentId: string) {
  const queryClient = useQueryClient();
  const incident = useIncident(incidentId);
  // Read again on every answer and every failure (each changes one of the two), so the count and
  // the status are current.
  const state =
    incident.dataUpdatedAt > 0 || incident.errorUpdatedAt > 0
      ? queryClient.getQueryState<Incident>(incidentKeys.detail(incidentId))
      : undefined;
  return {
    incident,
    addressWaitEnded:
      state?.data?.location.addressStatus === 'PENDING' && !waitingForAddress(state),
  };
}

/**
 * Only successful reads count, and a failed read stops it too: the screen keeps the report with
 * its notice, never a poll that keeps failing. The count belongs to the cached query, so it
 * survives a navigation while it is kept.
 */
function waitingForAddress(state: QueryState<Incident>): boolean {
  return (
    state.status === 'success' &&
    state.data?.location.addressStatus === 'PENDING' &&
    state.dataUpdateCount <= PENDING_POLL_CHECKS
  );
}

/** The history the citizen may see: the API leaves out the internal entries and reasons. */
export function useIncidentHistory(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.history(incidentId),
    queryFn: () => dataOf(listIncidentStatusChanges(api, incidentId)),
  });
}

/** The photos the citizen uploaded to the report: the API leaves out any other. */
export function useIncidentAttachments(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.attachments(incidentId),
    queryFn: () => dataOf(listIncidentAttachments(api, incidentId)),
  });
}

/**
 * The file of one photo as a URI for `Image`, downloaded with the session's token, never through a
 * public URL.
 */
export function useAttachmentContent(attachmentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: attachmentKeys.content(attachmentId),
    queryFn: async () => blobToDataUri(await dataOf(getAttachmentContent(api, attachmentId))),
    // A stored photo never changes, so it is not downloaded again.
    staleTime: Infinity,
  });
}

/**
 * Downloads again the photos that failed. They live outside {@link incidentKeys}, so a pull to
 * refresh does not reach them; a photo already loaded never changes and is left alone.
 */
export function refetchFailedPhotos(queryClient: QueryClient): Promise<void> {
  return queryClient.refetchQueries({
    queryKey: attachmentKeys.all,
    predicate: (query) => query.state.status === 'error',
  });
}

/** The active categories, for the optional category of the report form. */
export function useCategories() {
  const { api } = useSession();
  return useQuery({
    queryKey: categoryKeys.active,
    queryFn: () => dataOf(listCategories(api)),
  });
}
