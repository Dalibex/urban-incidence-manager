import createClient from 'openapi-fetch';

import type { paths } from './schema';
import { createSessionFetch, type SessionOptions } from './session';

// schema.d.ts is generated from docs/api/openapi.yml with `pnpm generate:api` and committed.
// Never edit it by hand: the `contract` CI job fails when it differs from the contract.
export type { components, paths } from './schema';
export type { SessionOptions, SessionTokens, TokenStore } from './session';
export {
  callApi,
  changePassword,
  getCurrentUser,
  signIn,
  signOut,
  signUp,
  type ApiFailure,
  type ApiResult,
} from './account';
export {
  acceptIncident,
  createIncident,
  getIncident,
  listCategories,
  listDepartments,
  listIncidents,
  listIncidentStatusChanges,
  resolveIncident,
  updateIncident,
  type CreateIncidentResult,
  type FieldError,
  type FormResult,
  type IncidentListQuery,
} from './incidents';
export {
  getAttachmentContent,
  listIncidentAttachments,
  uploadIncidentAttachment,
  type LocalFile,
  type UploadOptions,
} from './attachments';
export {
  downloadIncidentReport,
  getIncidentReport,
  type IncidentReport,
  type IncidentReportQuery,
} from './reports';

/**
 * Creates a client whose paths, parameters and responses are typed from the contract. Each app
 * passes the base URL from its own configuration, never a hard-coded host, and its token store:
 * the client sends the access token and refreshes the session on its own.
 */
export function createApiClient(baseUrl: string, session: SessionOptions) {
  return createClient<paths>({ baseUrl, fetch: createSessionFetch(baseUrl, session) });
}

export type ApiClient = ReturnType<typeof createApiClient>;
