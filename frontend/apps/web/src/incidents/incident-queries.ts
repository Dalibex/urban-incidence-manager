import {
  getIncident,
  listCategories,
  listDepartments,
  listIncidents,
  listIncidentStatusChanges,
  updateIncident,
  type components,
} from '@urbanpulse/api-client';
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { toListQuery, type IncidentFilters } from './filters/incident-filters';

/** Rows per page of the inbox; the API allows up to 100 (docs/api.md, "Listings"). */
export const INBOX_PAGE_SIZE = 20;

type Incident = components['schemas']['IncidentResponse'];
type UpdateIncidentRequest = components['schemas']['UpdateIncidentRequest'];

const incidentKeys = {
  page: (page: number, filters: IncidentFilters) => ['incidents', 'page', page, filters] as const,
  detail: (incidentId: string) => ['incidents', incidentId] as const,
  history: (incidentId: string) => ['incidents', incidentId, 'status-changes'] as const,
  categories: ['categories'] as const,
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

/**
 * The active categories, ordered by name: the options of the category filter and of the
 * classification panel. They change rarely.
 */
export function useCategories() {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.categories,
    queryFn: () => dataOf(listCategories(api)),
    staleTime: 5 * 60_000,
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

/**
 * What every change of an incident does to the cache, so the screens show it without reloading:
 * the detail takes the incident the API answered, with its new `version`, and the inbox pages and
 * the parts of this incident read separately (its history, for example) are read again. Every
 * mutation of the web calls it on success (#47 leaves the pattern for the lifecycle actions).
 */
export function afterIncidentChange(queryClient: QueryClient, incident: Incident): Promise<void> {
  queryClient.setQueryData(incidentKeys.detail(incident.id), incident);
  return queryClient.invalidateQueries({
    predicate: ({ queryKey }) =>
      (queryKey[0] === 'incidents' && queryKey[1] === 'page') ||
      (queryKey[0] === 'incidents' && queryKey[1] === incident.id && queryKey.length > 2),
  });
}

/**
 * Classifies the incident (#47). The call resolves with the API's answer, a failure included, so
 * the form can show it next to its fields; only an unexpected bug rejects.
 */
export function useUpdateIncident(incidentId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateIncidentRequest) => updateIncident(api, incidentId, body),
    onSuccess: (result) => {
      if (result.ok) {
        // Not awaited: the form answers as soon as the API does, while the inbox reloads.
        void afterIncidentChange(queryClient, result.data);
      }
    },
  });
}

/** Reads the incident again, for example after a `409` that says it changed meanwhile. */
export function useReloadIncident(incidentId: string) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: incidentKeys.detail(incidentId) });
}
