import {
  getAttachmentContent,
  getIncident,
  listCategories,
  listDepartments,
  listIncidentAttachments,
  listIncidents,
  listIncidentStatusChanges,
} from '@urbanpulse/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { toListQuery, type IncidentFilters } from './filters/incident-filters';

/** Rows per page of the inbox; the API allows up to 100 (docs/api.md, "Listings"). */
export const INBOX_PAGE_SIZE = 20;

const incidentKeys = {
  page: (page: number, filters: IncidentFilters) => ['incidents', 'page', page, filters] as const,
  detail: (incidentId: string) => ['incidents', incidentId] as const,
  history: (incidentId: string) => ['incidents', incidentId, 'status-changes'] as const,
  attachments: (incidentId: string) => ['incidents', incidentId, 'attachments'] as const,
};

/**
 * One page of the incidents the user may see that match `filters`, newest first (the API's default
 * order, with `id` as the tie-break, so no row appears on two pages).
 *
 * @param page starting at 0, as in the API
 */
export function useIncidentPage(page: number, filters: IncidentFilters) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.page(page, filters),
    queryFn: () =>
      dataOf(listIncidents(api, { page, size: INBOX_PAGE_SIZE, ...toListQuery(filters) })),
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

/** The metadata of the photos of an incident. */
export function useIncidentAttachments(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.attachments(incidentId),
    queryFn: () => dataOf(listIncidentAttachments(api, incidentId)),
  });
}

/** The file of one photo, downloaded with the session's token, never through a public URL. */
export function useAttachmentContent(attachmentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: ['attachments', attachmentId, 'content'],
    queryFn: () => dataOf(getAttachmentContent(api, attachmentId)),
    // A stored photo never changes (the API has no way to edit one), so it is not downloaded again.
    staleTime: Infinity,
  });
}

/** The active categories, ordered by name: the options of the category filter. */
export function useCategories() {
  const { api } = useSession();
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => dataOf(listCategories(api)),
  });
}

/** The active departments, ordered by name: the options of the department filter. */
export function useDepartments() {
  const { api } = useSession();
  return useQuery({
    queryKey: ['departments'],
    queryFn: () => dataOf(listDepartments(api)),
  });
}
