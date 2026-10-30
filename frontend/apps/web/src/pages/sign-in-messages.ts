import type { ApiFailure } from '@urbanpulse/api-client';

export const UNEXPECTED_SIGN_IN_ERROR = 'Sign-in failed. Try again in a moment.';

/** Message for a failed sign-in, chosen from the problem `code` and status, never its text. */
export function signInMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return 'UrbanPulse cannot be reached. Check your connection and try again.';
  }
  switch (failure.code) {
    case 'INVALID_CREDENTIALS':
      return 'The email or the password is not correct.';
    case 'ACCOUNT_DISABLED':
      return 'This account is disabled. Ask an administrator to enable it.';
  }
  switch (failure.status) {
    case 400:
    case 422:
      return 'Enter a valid email and your password.';
    case 429:
      return 'Too many attempts. Wait a few minutes and try again.';
    default:
      return UNEXPECTED_SIGN_IN_ERROR;
  }
}
