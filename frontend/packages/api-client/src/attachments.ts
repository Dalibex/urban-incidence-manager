import type { ApiClient } from './index';
import { isProblem, type ApiResult } from './account';
import type { components } from './schema';
import { withoutTrailingSlashes, type SessionTokens, type TokenStore } from './session';

type Attachment = components['schemas']['AttachmentResponse'];

/**
 * A file part as React Native's `FormData` takes it: the device reads the bytes from `uri`, so the
 * photo is never loaded into JavaScript memory. `name` is only shown; the server never uses it.
 */
export interface LocalFile {
  uri: string;
  name: string;
  type: string;
}

export interface UploadOptions {
  /** The same base URL the client was created with. */
  baseUrl: string;
  /** The same token store the client was created with. */
  tokenStore: TokenStore;
  /** Fraction of the file sent so far, from 0 to 1. */
  onProgress?: (fraction: number) => void;
  /** Replaces `XMLHttpRequest`, for tests. */
  createRequest?: () => XMLHttpRequest;
}

/** A photo on a slow mobile network takes long, but a stalled upload must end in an error. */
const UPLOAD_TIMEOUT_MS = 60_000;

type Reply = { kind: 'network' } | { kind: 'response'; status: number; body: unknown };

/**
 * Uploads one photo to an incident (`createIncidentAttachment`). It uses `XMLHttpRequest` instead
 * of the client's `fetch` because only XHR reports upload progress in React Native.
 *
 * A retry with the same content is safe: the API stores it once and answers `200` with the existing
 * attachment. An expired access token is refreshed through the client, which owns the refresh
 * (session.ts), and the upload is repeated once.
 */
export async function uploadIncidentAttachment(
  client: ApiClient,
  incidentId: string,
  file: LocalFile,
  options: UploadOptions,
): Promise<ApiResult<Attachment>> {
  const url = `${withoutTrailingSlashes(options.baseUrl)}/incidents/${encodeURIComponent(incidentId)}/attachments`;
  const tokens = await options.tokenStore.load();
  let reply = await send(url, file, tokens, options);
  if (reply.kind === 'response' && reply.status === 401 && tokens) {
    try {
      // Any call through the client refreshes the session when its access token was rejected.
      await client.GET('/users/me');
    } catch (error) {
      if (error instanceof TypeError) {
        return { ok: false, failure: { kind: 'network' } };
      }
      throw error;
    }
    const fresh = await options.tokenStore.load();
    if (fresh && fresh.accessToken !== tokens.accessToken) {
      reply = await send(url, file, fresh, options);
    }
  }
  return toResult(reply);
}

function send(
  url: string,
  file: LocalFile,
  tokens: SessionTokens | null,
  { onProgress, createRequest }: UploadOptions,
): Promise<Reply> {
  return new Promise((resolve) => {
    const request = createRequest ? createRequest() : new XMLHttpRequest();
    request.open('POST', url);
    request.timeout = UPLOAD_TIMEOUT_MS;
    request.setRequestHeader('Accept', 'application/json');
    if (tokens) {
      request.setRequestHeader('Authorization', `${tokens.tokenType} ${tokens.accessToken}`);
    }
    request.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        // React Native on Android can report more bytes sent than the total; a fraction stays <= 1.
        onProgress?.(Math.min(event.loaded / event.total, 1));
      }
    };
    request.onload = () =>
      resolve({ kind: 'response', status: request.status, body: parseJson(request.responseText) });
    const failed = () => resolve({ kind: 'network' });
    request.onerror = failed;
    request.ontimeout = failed;
    request.onabort = failed;

    const form = new FormData();
    // React Native reads a `{ uri, name, type }` part from the device; the DOM typings only know Blob.
    // No Content-Type header: XHR sets it with the multipart boundary.
    form.append('file', file as unknown as Blob);
    request.send(form);
  });
}

function toResult(reply: Reply): ApiResult<Attachment> {
  if (reply.kind === 'network') {
    return { ok: false, failure: { kind: 'network' } };
  }
  // 201 when stored, 200 when the same content was already attached.
  if (reply.status === 200 || reply.status === 201) {
    // The contract always carries the attachment; anything else is an implementation bug.
    if (!isAttachment(reply.body)) {
      throw new Error('The API did not return the attachment.');
    }
    return { ok: true, data: reply.body };
  }
  const code = isProblem(reply.body) ? reply.body.code : undefined;
  return { ok: false, failure: { kind: 'problem', status: reply.status, code } };
}

function isAttachment(value: unknown): value is Attachment {
  return typeof value === 'object' && value !== null && 'id' in value;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}
