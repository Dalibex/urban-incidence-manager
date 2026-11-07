import type { ApiClient } from './index';
import { callApi, type ApiResult } from './account';
import type { components } from './schema';

type Attachment = components['schemas']['AttachmentResponse'];

/** The metadata of the photos of an incident. The files themselves come from `getAttachmentContent`. */
export function listIncidentAttachments(
  client: ApiClient,
  incidentId: string,
): Promise<ApiResult<Attachment[]>> {
  return callApi(() =>
    client.GET('/incidents/{incidentId}/attachments', { params: { path: { incidentId } } }),
  );
}

/**
 * The file of a photo, downloaded with the session's access token. There is no public URL for it:
 * a client shows the `Blob` through an object URL of its own.
 */
export function getAttachmentContent(
  client: ApiClient,
  attachmentId: string,
): Promise<ApiResult<Blob>> {
  return callApi(() =>
    client.GET('/attachments/{attachmentId}/content', {
      params: { path: { attachmentId } },
      parseAs: 'blob',
    }),
  );
}
