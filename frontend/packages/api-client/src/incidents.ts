import { callApi, type ApiResult } from './account';
import type { ApiClient } from './index';
import type { components, operations } from './schema';

type Schemas = components['schemas'];

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
