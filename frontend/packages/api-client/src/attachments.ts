import type { ApiClient } from './index';
import { callApi, isProblem, type ApiFailure, type ApiResult } from './account';
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
  /** Replaces the clock that tells whether the access token has expired, for tests. */
  now?: () => Date;
}

/**
 * How long an upload may go without sending a byte or receiving the answer. A photo on a slow
 * mobile network may take minutes and still be moving, so there is no limit for the whole upload;
 * only a stalled one ends in an error.
 */
const STALL_TIMEOUT_MS = 60_000;

type Reply = { kind: 'network' } | { kind: 'response'; status: number; body: unknown };

/**
 * Uploads one photo to an incident (`createIncidentAttachment`). It uses `XMLHttpRequest` instead
 * of the client's `fetch` because only XHR reports upload progress in React Native.
 *
 * A retry with the same content is safe: the API stores it once and answers `200` with the existing
 * attachment. The client owns the refresh (session.ts): an access token that has already expired is
 * refreshed with a small request before sending the photo, so the photo is not sent twice; if the
 * API still answers `401` (a clock that runs late), the session is refreshed and the upload is
 * repeated once.
 */
export async function uploadIncidentAttachment(
  client: ApiClient,
  incidentId: string,
  file: LocalFile,
  options: UploadOptions,
): Promise<ApiResult<Attachment>> {
  const url = `${withoutTrailingSlashes(options.baseUrl)}/incidents/${encodeURIComponent(incidentId)}/attachments`;
  const now = options.now ?? (() => new Date());
  let tokens = await options.tokenStore.load();
  if (tokens && hasExpired(tokens, now())) {
    const failure = await refreshThroughClient(client);
    if (failure) {
      return { ok: false, failure };
    }
    tokens = await options.tokenStore.load();
  }
  let reply = await send(url, file, tokens, options);
  if (reply.kind === 'response' && reply.status === 401 && tokens) {
    if ((await refreshThroughClient(client))?.kind === 'network') {
      return { ok: false, failure: { kind: 'network' } };
    }
    const fresh = await options.tokenStore.load();
    if (fresh && fresh.accessToken !== tokens.accessToken) {
      // The whole photo is sent again, so the bar starts again instead of staying at 100%.
      options.onProgress?.(0);
      reply = await send(url, file, fresh, options);
    }
  }
  return toResult(reply);
}

function hasExpired(tokens: SessionTokens, now: Date): boolean {
  return Date.parse(tokens.accessTokenExpiresAt) <= now.getTime();
}

/**
 * Any call through the client refreshes the session when its access token is rejected. Returns why
 * it failed, or `null` when the call succeeded.
 */
async function refreshThroughClient(client: ApiClient): Promise<ApiFailure | null> {
  const result = await callApi(() => client.GET('/users/me'));
  return result.ok ? null : result.failure;
}

function send(
  url: string,
  file: LocalFile,
  tokens: SessionTokens | null,
  { onProgress, createRequest }: UploadOptions,
): Promise<Reply> {
  return new Promise((resolve) => {
    const request = createRequest ? createRequest() : new XMLHttpRequest();
    let stallTimer: ReturnType<typeof setTimeout> | undefined;
    const settle = (reply: Reply) => {
      clearTimeout(stallTimer);
      resolve(reply);
    };
    const restartStallTimer = () => {
      clearTimeout(stallTimer);
      stallTimer = setTimeout(() => {
        settle({ kind: 'network' });
        request.abort();
      }, STALL_TIMEOUT_MS);
    };

    request.open('POST', url);
    request.setRequestHeader('Accept', 'application/json');
    if (tokens) {
      request.setRequestHeader('Authorization', `${tokens.tokenType} ${tokens.accessToken}`);
    }
    request.upload.onprogress = (event) => {
      restartStallTimer();
      if (event.lengthComputable && event.total > 0) {
        // React Native on Android can report more bytes sent than the total; a fraction stays <= 1.
        onProgress?.(Math.min(event.loaded / event.total, 1));
      }
    };
    request.onload = () =>
      settle({ kind: 'response', status: request.status, body: parseJson(request.responseText) });
    const failed = () => settle({ kind: 'network' });
    request.onerror = failed;
    request.ontimeout = failed;
    request.onabort = failed;

    const form = new FormData();
    // React Native reads a `{ uri, name, type }` part from the device; the DOM typings only know Blob.
    // No Content-Type header: XHR sets it with the multipart boundary.
    form.append('file', file as unknown as Blob);
    restartStallTimer();
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
