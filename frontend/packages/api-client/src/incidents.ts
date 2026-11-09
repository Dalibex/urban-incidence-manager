import type { ApiClient } from './index';
import { callApi, isProblem, type ApiFailure, type ApiResult } from './account';
import type { components, operations } from './schema';

type Category = components['schemas']['CategoryResponse'];
type CreateIncidentRequest = components['schemas']['CreateIncidentRequest'];
type IncidentResponse = components['schemas']['IncidentResponse'];
type UpdateIncidentRequest = components['schemas']['UpdateIncidentRequest'];
type Schemas = components['schemas'];

/** One invalid field of a `422` problem body (`Problem.errors` items). */
export type FieldError = components['schemas']['FieldError'];

/**
 * Result of a call that sends a form. A `422` carries `fieldErrors`, one entry per invalid field as
 * the contract defines in `Problem.errors`, so a form can show each message under its input.
 */
export type FormResult<T> =
  { ok: true; data: T } | { ok: false; failure: ApiFailure; fieldErrors: FieldError[] };

/** Result of reporting an incident. */
export type CreateIncidentResult = FormResult<IncidentResponse>;

/** Reports an incident (RF03). The API takes the reporter from the access token of the session. */
export function createIncident(
  client: ApiClient,
  body: CreateIncidentRequest,
): Promise<CreateIncidentResult> {
  return callForm(() => client.POST('/incidents', { body }), 'the created incident');
}

/**
 * Sets or corrects the category and the priority of an incident (#47). Send the `version` the
 * detail returned: a stale one answers `409` with `CONCURRENT_MODIFICATION`, and the incident must
 * be reloaded before deciding again.
 */
export function updateIncident(
  client: ApiClient,
  incidentId: string,
  body: UpdateIncidentRequest,
): Promise<FormResult<IncidentResponse>> {
  return callForm(
    () => client.PATCH('/incidents/{incidentId}', { params: { path: { incidentId } }, body }),
    'the incident',
  );
}

/**
 * The technician of the active assignment accepts the incident: `ASSIGNED` to `IN_PROGRESS`
 * (ADR-0020). A stale `version` answers `409` with `CONCURRENT_MODIFICATION`, a status that does not
 * allow it `409` with `INVALID_TRANSITION`, and an incident no longer assigned to them `404`.
 */
export function acceptIncident(
  client: ApiClient,
  incidentId: string,
  body: Schemas['IncidentActionRequest'],
): Promise<FormResult<IncidentResponse>> {
  return callForm(
    () =>
      client.POST('/incidents/{incidentId}/acceptance', {
        params: { path: { incidentId } },
        body,
      }),
    'the incident',
  );
}

/**
 * The technician of the active assignment resolves the incident: `IN_PROGRESS` to `RESOLVED`
 * (ADR-0020). `reason` holds the resolution notes, which the citizen never sees. It answers as
 * {@link acceptIncident} does, and `422` when the notes are missing.
 */
export function resolveIncident(
  client: ApiClient,
  incidentId: string,
  body: Schemas['IncidentReasonRequest'],
): Promise<FormResult<IncidentResponse>> {
  return callForm(
    () =>
      client.POST('/incidents/{incidentId}/resolution', {
        params: { path: { incidentId } },
        body,
      }),
    'the incident',
  );
}

/**
 * Adds an internal comment to an incident (#50). Citizens never see it. Staff who may not see the
 * incident, such as a technician it is no longer assigned to, get `404`.
 */
export function createIncidentComment(
  client: ApiClient,
  incidentId: string,
  body: Schemas['CreateCommentRequest'],
): Promise<FormResult<Schemas['CommentResponse']>> {
  return callForm(
    () =>
      client.POST('/incidents/{incidentId}/comments', {
        params: { path: { incidentId } },
        body,
      }),
    'the created comment',
  );
}

async function callForm<T>(
  call: () => Promise<{ data?: T; error?: unknown; response: Response }>,
  what: string,
): Promise<FormResult<T>> {
  let result: { data?: T; error?: unknown; response: Response };
  try {
    result = await call();
  } catch (error) {
    // `fetch` rejects with a TypeError when the API cannot be reached; anything else is a bug.
    if (error instanceof TypeError) {
      return { ok: false, failure: { kind: 'network' }, fieldErrors: [] };
    }
    throw error;
  }
  if (result.response.ok) {
    // The contract always returns the resource on success; anything else is an implementation bug.
    if (!result.data) {
      throw new Error(`The API did not return ${what}.`);
    }
    return { ok: true, data: result.data };
  }
  const problem = isProblem(result.error) ? result.error : undefined;
  return {
    ok: false,
    failure: { kind: 'problem', status: result.response.status, code: problem?.code },
    fieldErrors: problem?.errors ?? [],
  };
}

/** The active categories, ordered by name, for the category choice of the report form. */
export function listCategories(client: ApiClient): Promise<ApiResult<Category[]>> {
  return callApi(() => client.GET('/categories'));
}

/** The active departments, ordered by name, for the department filter of staff listings. */
export function listDepartments(
  client: ApiClient,
): Promise<ApiResult<Schemas['DepartmentResponse'][]>> {
  return callApi(() => client.GET('/departments'));
}

/** Query of `listIncidents`: `page` starts at 0; omitted values take the contract's defaults. */
export type IncidentListQuery = NonNullable<operations['listIncidents']['parameters']['query']>;

/** A page of incidents the caller may see, newest first unless `sort` says otherwise. */
export function listIncidents(
  client: ApiClient,
  query: IncidentListQuery = {},
): Promise<ApiResult<Schemas['IncidentPageResponse']>> {
  return callApi(() => client.GET('/incidents', { params: { query } }));
}

/** The full incident. Someone else's incident answers `404`, the same as one that does not exist. */
export function getIncident(
  client: ApiClient,
  incidentId: string,
): Promise<ApiResult<Schemas['IncidentResponse']>> {
  return callApi(() => client.GET('/incidents/{incidentId}', { params: { path: { incidentId } } }));
}

/** The status history of an incident, oldest first. */
export function listIncidentStatusChanges(
  client: ApiClient,
  incidentId: string,
): Promise<ApiResult<Schemas['StatusChangeResponse'][]>> {
  return callApi(() =>
    client.GET('/incidents/{incidentId}/status-changes', { params: { path: { incidentId } } }),
  );
}
