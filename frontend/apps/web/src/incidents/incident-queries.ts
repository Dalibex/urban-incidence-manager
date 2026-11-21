import {
  acceptIncident,
  assignIncident,
  closeIncident,
  createIncidentComment,
  getAttachmentContent,
  getIncident,
  getIncidentStatistics,
  getIncidentUrbanContext,
  hasSessionEnded,
  listCategories,
  listDepartments,
  listIncidentAttachments,
  listIncidentClassificationChanges,
  listIncidentComments,
  listIncidentLocations,
  listIncidents,
  listIncidentStatusChanges,
  listTechnicians,
  rejectIncident,
  reopenIncident,
  requestIncidentInformation,
  resolveIncident,
  updateIncident,
  validateIncident,
  type ApiClient,
  type ApiResult,
  type components,
  type FormResult,
} from '@urbanpulse/api-client';
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
  type QueryState,
} from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { toListQuery, type IncidentFilters } from './filters/incident-filters';

/** Rows per page of the inbox; the API allows up to 100 (docs/api.md, "Listings"). */
export const INBOX_PAGE_SIZE = 20;

/** How often the map reads the positions again, so a new incident appears without a reload. */
export const MAP_REFRESH_INTERVAL_MS = 15_000;

type Incident = components['schemas']['IncidentResponse'];
type UpdateIncidentRequest = components['schemas']['UpdateIncidentRequest'];
type IncidentActionRequest = components['schemas']['IncidentActionRequest'];
type IncidentReasonRequest = components['schemas']['IncidentReasonRequest'];
type CreateAssignmentRequest = components['schemas']['CreateAssignmentRequest'];

const incidentKeys = {
  page: (page: number, filters: IncidentFilters) => ['incidents', 'page', page, filters] as const,
  locations: (filters: IncidentFilters) => ['incidents', 'locations', filters] as const,
  statistics: (filters: IncidentFilters) => ['incidents', 'statistics', filters] as const,
  latest: (filters: IncidentFilters) => ['incidents', 'latest', filters] as const,
  detail: (incidentId: string) => ['incidents', incidentId] as const,
  history: (incidentId: string) => ['incidents', incidentId, 'status-changes'] as const,
  classificationChanges: (incidentId: string) =>
    ['incidents', incidentId, 'classification-changes'] as const,
  attachments: (incidentId: string) => ['incidents', incidentId, 'attachments'] as const,
  comments: (incidentId: string) => ['incidents', incidentId, 'comments'] as const,
  urbanContext: (incidentId: string) => ['incidents', incidentId, 'urban-context'] as const,
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

/**
 * The positions of the incidents the user may see that match `filters`, for the map (#196): the
 * most relevant first, at most the API's cap. They are read again every
 * {@link MAP_REFRESH_INTERVAL_MS}; a failed refresh keeps the last positions (`data`) next to the
 * error, and the next interval tries again.
 */
export function useIncidentLocations(filters: IncidentFilters) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.locations(filters),
    queryFn: () => dataOf(listIncidentLocations(api, toListQuery(filters))),
    refetchInterval: MAP_REFRESH_INTERVAL_MS,
    // The markers stay on the map while other filters load, instead of the map emptying.
    placeholderData: keepPreviousData,
  });
}

/**
 * The counts of the dashboard beside the map (#177), with the same filters and read on the same
 * interval as {@link useIncidentLocations}. They are two requests with their own timers, so for a
 * few seconds a new incident may be on the map and not yet counted, or the reverse. A failed
 * refresh keeps the last counts (`data`) next to the error, as the map does.
 */
export function useIncidentStatistics(filters: IncidentFilters) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.statistics(filters),
    queryFn: () => dataOf(getIncidentStatistics(api, toListQuery(filters))),
    refetchInterval: MAP_REFRESH_INTERVAL_MS,
    placeholderData: keepPreviousData,
  });
}

/** How many of the latest incidents the dashboard of the map lists. */
export const LATEST_COUNT = 5;

/**
 * The latest incidents reported that match the filters of the map (#177): the first page of
 * `listIncidents`, newest first, read again with the map.
 */
export function useLatestIncidents(filters: IncidentFilters) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.latest(filters),
    queryFn: () =>
      dataOf(listIncidents(api, { page: 0, size: LATEST_COUNT, ...toListQuery(filters) })),
    refetchInterval: MAP_REFRESH_INTERVAL_MS,
    placeholderData: keepPreviousData,
  });
}

/**
 * @param refetchInterval how often to read it again while it is shown, in ms; the map's summary
 *   reads it with the markers, so it never shows an older status than its marker (#287)
 */
export function useIncident(incidentId: string, refetchInterval?: number) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.detail(incidentId),
    queryFn: () => dataOf(getIncident(api, incidentId)),
    refetchInterval,
  });
}

/** How often a pending weather is read again: the backend's worker runs every 30 s (ADR-0018). */
export const PENDING_CONTEXT_INTERVAL_MS = 15_000;

/** Reads of a pending weather before the section stops on its own: two minutes in all. */
export const PENDING_CONTEXT_CHECKS = 8;

/**
 * The urban context of an incident. While it is `PENDING`, it is read again every 15 s, up to two
 * minutes; then the section offers to check again instead of polling for ever.
 */
export function useIncidentUrbanContext(incidentId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const queryKey = incidentKeys.urbanContext(incidentId);
  const context = useQuery({
    queryKey,
    queryFn: () => dataOf(getIncidentUrbanContext(api, incidentId)),
    refetchInterval: (query) =>
      waitingForWeather(query.state) === 'polling' ? PENDING_CONTEXT_INTERVAL_MS : false,
  });
  // Read again on every answer (dataUpdatedAt changes with each one), so the count is current.
  const state =
    context.dataUpdatedAt > 0
      ? queryClient.getQueryState<components['schemas']['UrbanContextResponse']>(queryKey)
      : undefined;
  return { context, stoppedWaiting: state ? waitingForWeather(state) === 'stopped' : false };
}

/**
 * Whether a pending weather is still read on its own. Only successful reads count, and a failed
 * read stops it too: the section then shows the failure with *Try again*, never a poll that keeps
 * failing. The count belongs to the cached query, so it survives a navigation while it is kept.
 */
function waitingForWeather(
  state: QueryState<components['schemas']['UrbanContextResponse']>,
): 'polling' | 'stopped' | 'no' {
  if (state.status !== 'success' || state.data?.status !== 'PENDING') {
    return 'no';
  }
  return state.dataUpdateCount <= PENDING_CONTEXT_CHECKS ? 'polling' : 'stopped';
}

export function useIncidentHistory(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.history(incidentId),
    queryFn: () => dataOf(listIncidentStatusChanges(api, incidentId)),
  });
}

/** The most the API returns in one page (docs/api.md, "Listings"). */
const MAX_PAGE_SIZE = 100;

interface Page<T> {
  content: T[];
  page: { totalPages: number };
}

/** Every item of a paged listing, read page after page until the last one. */
async function readAllPages<T>(
  readPage: (query: { page: number; size: number }) => Promise<ApiResult<Page<T>>>,
): Promise<T[]> {
  const items: T[] = [];
  for (let page = 0; ; page++) {
    const result = await dataOf(readPage({ page, size: MAX_PAGE_SIZE }));
    items.push(...result.content);
    if (page + 1 >= result.page.totalPages) {
      return items;
    }
  }
}

/** Every classification change of the incident, oldest first, for the timeline of staff (#169). */
export function useClassificationChanges(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.classificationChanges(incidentId),
    queryFn: () =>
      readAllPages((query) => listIncidentClassificationChanges(api, incidentId, query)),
  });
}

/** Every internal comment of the incident, oldest first, for the timeline of staff (#50). */
export function useIncidentComments(incidentId: string) {
  const { api } = useSession();
  return useQuery({
    queryKey: incidentKeys.comments(incidentId),
    queryFn: () => readAllPages((query) => listIncidentComments(api, incidentId, query)),
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

/** The active departments, ordered by name: the options of the department filter and the assignment. */
export function useDepartments() {
  const { api } = useSession();
  return useQuery({
    queryKey: ['departments'],
    queryFn: () => dataOf(listDepartments(api)),
  });
}

/** The active technicians, ordered by name: the options of the assignment form (#185). */
export function useTechnicians() {
  const { api } = useSession();
  return useQuery({
    queryKey: ['technicians'],
    queryFn: () => dataOf(listTechnicians(api)),
  });
}

/** The lists that a change of any incident can alter: the inbox, the map and its dashboard. */
const INCIDENT_LISTS: readonly unknown[] = ['page', 'locations', 'statistics', 'latest'];

/**
 * What every change of an incident does to the cache, so the screens show it without reloading:
 * the detail takes the incident the API answered, with its new `version`, and the inbox pages, the
 * map, its dashboard and the parts of this incident read separately (its history, for example) are
 * read again. Every mutation of the web calls it on success (#47 leaves the pattern for the
 * lifecycle actions).
 *
 * A reload of the detail still in flight is cancelled first: it was sent before the change, and
 * answering after it, it would bring back the old status and `version` (#287).
 */
export async function afterIncidentChange(
  queryClient: QueryClient,
  incident: Incident,
): Promise<void> {
  const detail = incidentKeys.detail(incident.id);
  await queryClient.cancelQueries({ queryKey: detail, exact: true });
  queryClient.setQueryData(detail, incident);
  await queryClient.invalidateQueries({
    predicate: ({ queryKey }) =>
      (queryKey[0] === 'incidents' && INCIDENT_LISTS.includes(queryKey[1])) ||
      (queryKey[0] === 'incidents' && queryKey[1] === incident.id && queryKey.length > 2),
  });
}

/**
 * A change of the incident sent from a form. The call resolves with the API's answer, a failure
 * included, so the form can show it next to its fields; only an unexpected bug rejects.
 */
function useIncidentChange<TBody>(
  send: (api: ApiClient, body: TBody) => Promise<FormResult<Incident>>,
) {
  const { api, markSession } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    // The session that sends the change, kept as the mutation's context.
    onMutate: () => markSession(),
    mutationFn: (body: TBody) => send(api, body),
    onSuccess: (result, _body, session) => {
      // After a sign-out the cache belongs to nobody, or to the next user: the answer stays out.
      if (result.ok && !hasSessionEnded(session)) {
        // Not awaited: the form answers as soon as the API does, while the lists reload.
        void afterIncidentChange(queryClient, result.data);
      }
    },
  });
}

/** Classifies the incident (#47). */
export function useUpdateIncident(incidentId: string) {
  return useIncidentChange((api, body: UpdateIncidentRequest) =>
    updateIncident(api, incidentId, body),
  );
}

/** The technician accepts the incident assigned to them (#187). */
export function useAcceptIncident(incidentId: string) {
  return useIncidentChange((api, body: IncidentActionRequest) =>
    acceptIncident(api, incidentId, body),
  );
}

/** The technician resolves the incident, with the resolution notes (#187). */
export function useResolveIncident(incidentId: string) {
  return useIncidentChange((api, body: IncidentReasonRequest) =>
    resolveIncident(api, incidentId, body),
  );
}

/** The operator validates the reported incident once it is classified (#189). */
export function useValidateIncident(incidentId: string) {
  return useIncidentChange((api, body: IncidentActionRequest) =>
    validateIncident(api, incidentId, body),
  );
}

/** The operator asks the citizen for more information on the reported incident (#189). */
export function useRequestIncidentInformation(incidentId: string) {
  return useIncidentChange((api, body: IncidentReasonRequest) =>
    requestIncidentInformation(api, incidentId, body),
  );
}

/** The operator rejects the reported incident, with the reason the citizen sees (#189). */
export function useRejectIncident(incidentId: string) {
  return useIncidentChange((api, body: IncidentReasonRequest) =>
    rejectIncident(api, incidentId, body),
  );
}

/** The operator assigns the incident, or reassigns an assigned one (#185). */
export function useAssignIncident(incidentId: string) {
  return useIncidentChange((api, body: CreateAssignmentRequest) =>
    assignIncident(api, incidentId, body),
  );
}

/** The operator closes the resolved incident after checking the work (#52). */
export function useCloseIncident(incidentId: string) {
  return useIncidentChange((api, body: IncidentActionRequest) =>
    closeIncident(api, incidentId, body),
  );
}

/** The operator reopens the resolved incident, with the reason the API requires (#52). */
export function useReopenIncident(incidentId: string) {
  return useIncidentChange((api, body: IncidentReasonRequest) =>
    reopenIncident(api, incidentId, body),
  );
}

/**
 * Adds an internal comment (#50). Like the changes of the incident, it resolves with the API's
 * answer, a failure included. A comment does not change the incident, so only the comments of the
 * timeline are read again.
 */
export function useAddComment(incidentId: string) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => createIncidentComment(api, incidentId, { text }),
    onSuccess: (result) => {
      if (result.ok) {
        void queryClient.invalidateQueries({ queryKey: incidentKeys.comments(incidentId) });
      }
    },
  });
}

/**
 * Reads the incident again, for example after a `409` that says it changed meanwhile. It resolves
 * with the incident as the cache then holds it: the one just read, or the earlier one if it could
 * not be read.
 */
export function useReloadIncident(incidentId: string) {
  const queryClient = useQueryClient();
  return async (): Promise<Incident | undefined> => {
    await queryClient.invalidateQueries({ queryKey: incidentKeys.detail(incidentId) });
    return queryClient.getQueryData<Incident>(incidentKeys.detail(incidentId));
  };
}
