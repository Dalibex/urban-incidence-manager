import type { ApiClient } from './index';
import { callApi, isProblem, type ApiFailure, type ApiResult } from './account';
import type { components, operations } from './schema';

type Category = components['schemas']['CategoryResponse'];
type CreateIncidentRequest = components['schemas']['CreateIncidentRequest'];
type IncidentResponse = components['schemas']['IncidentResponse'];
type Schemas = components['schemas'];

/** One invalid field of a `422` problem body (`Problem.errors` items). */
export type FieldError = components['schemas']['FieldError'];

/**
 * Result of reporting an incident. A `422` carries `fieldErrors`, one entry per invalid field as
 * the contract defines in `Problem.errors`, so a form can show each message under its input.
 */
export type CreateIncidentResult =
  | { ok: true; data: IncidentResponse }
  | { ok: false; failure: ApiFailure; fieldErrors: FieldError[] };

/** Reports an incident (RF03). The API takes the reporter from the access token of the session. */
export async function createIncident(
  client: ApiClient,
  body: CreateIncidentRequest,
): Promise<CreateIncidentResult> {
  let result: { data?: IncidentResponse; error?: unknown; response: Response };
  try {
    result = await client.POST('/incidents', { body });
  } catch (error) {
    // `fetch` rejects with a TypeError when the API cannot be reached; anything else is a bug.
    if (error instanceof TypeError) {
      return { ok: false, failure: { kind: 'network' }, fieldErrors: [] };
    }
    throw error;
  }
  if (result.response.ok) {
    // The 201 contract always carries the incident; anything else is an implementation bug.
    if (!result.data) {
      throw new Error('The API did not return the created incident.');
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
