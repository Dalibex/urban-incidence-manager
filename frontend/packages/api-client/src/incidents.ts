import type { ApiClient } from './index';
import { isProblem, type ApiFailure } from './account';
import type { components } from './schema';

type CreateIncidentRequest = components['schemas']['CreateIncidentRequest'];
type IncidentResponse = components['schemas']['IncidentResponse'];

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
type Category = components['schemas']['CategoryResponse'];

export async function listCategories(client: ApiClient): Promise<Category[]> {
  const result = await client.GET('/categories');

  if (result.error || !result.data) {
    throw new Error('Failed to load categories');
  }

  return result.data;
}
