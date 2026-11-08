import type { ApiFailure } from '@urbanpulse/api-client';

export const UNEXPECTED_PASSWORD_CHANGE_ERROR =
  'The password could not be changed. Try again in a moment.';

/** The rules of `CreatePasswordChangeRequest.newPassword`, checked before sending. */
export const NEW_PASSWORD_MIN_LENGTH = 8;
const NEW_PASSWORD_MAX_BYTES = 72;

/**
 * Why the new password cannot be sent, or `null` when it can. The API checks the same rules; this
 * only saves a request and says which rule failed.
 */
export function newPasswordProblem(
  currentPassword: string,
  newPassword: string,
  repeated: string,
): string | null {
  if (newPassword.length < NEW_PASSWORD_MIN_LENGTH) {
    return `The new password must have at least ${NEW_PASSWORD_MIN_LENGTH} characters.`;
  }
  // bcrypt reads 72 bytes, and a letter such as "ñ" takes two of them.
  if (new TextEncoder().encode(newPassword).length > NEW_PASSWORD_MAX_BYTES) {
    return 'The new password is too long. Use a shorter one.';
  }
  if (newPassword === currentPassword) {
    return 'The new password must be different from the one you were given.';
  }
  if (newPassword !== repeated) {
    return 'The two new passwords do not match.';
  }
  return null;
}

/** Message for a failed change, chosen from the problem `code` and status, never its text. */
export function passwordChangeMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return 'UrbanPulse cannot be reached. Check your connection and try again.';
  }
  switch (failure.code) {
    case 'INVALID_CREDENTIALS':
      return 'The password you signed in with is no longer valid. Go back and sign in again.';
    case 'ACCOUNT_DISABLED':
      return 'This account is disabled. Ask an administrator to enable it.';
  }
  switch (failure.status) {
    case 422:
      return `Choose another password: at least ${NEW_PASSWORD_MIN_LENGTH} characters, different from the one you were given.`;
    case 429:
      return 'Too many attempts. Wait a few minutes and try again.';
    default:
      return UNEXPECTED_PASSWORD_CHANGE_ERROR;
  }
}
