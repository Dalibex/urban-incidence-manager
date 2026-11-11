import type { ApiClient } from './index';
import { callApi, type ApiResult } from './account';
import type { components, operations } from './schema';

/** Query of `getIncidentReport`: `from` included and `to` excluded, both UTC instants. */
export type IncidentReportQuery = operations['getIncidentReport']['parameters']['query'];

export type IncidentReport = components['schemas']['IncidentReportResponse'];

/** The operational report of a period, grouped as `groupBy` says, for the screen. */
export async function getIncidentReport(
  client: ApiClient,
  query: IncidentReportQuery,
): Promise<ApiResult<IncidentReport>> {
  const result = await callApi(() =>
    client.GET('/incident-reports', {
      params: { query },
      headers: { Accept: 'application/json' },
    }),
  );
  // The generated type allows text because the operation also has a CSV representation. With
  // `Accept: application/json` the body is parsed as JSON, so it can only be the report: a CSV
  // would make the parse throw before getting here.
  return result as ApiResult<IncidentReport>;
}

/**
 * The same report as a CSV file, with the same rows. It goes through the client, not a plain link,
 * because the request needs the access token; errors still arrive as `problem+json`.
 */
export function downloadIncidentReport(
  client: ApiClient,
  query: IncidentReportQuery,
): Promise<ApiResult<Blob>> {
  return callApi(() =>
    client.GET('/incident-reports', {
      params: { query },
      headers: { Accept: 'text/csv' },
      parseAs: 'blob',
    }),
  );
}
