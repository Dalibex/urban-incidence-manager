import { ApiCallError } from '../query-client';

/**
 * A `404`, or a `400` for an id that is not a valid UUID: no incident the user may see has it.
 * Trying again does not help, so the screens say so instead of offering *Try again*.
 */
export function isNotFound(error: unknown): boolean {
  return (
    error instanceof ApiCallError &&
    error.failure.kind === 'problem' &&
    (error.failure.status === 404 || error.failure.status === 400)
  );
}
