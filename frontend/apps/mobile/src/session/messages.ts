import type { ApiFailure } from '@urbanpulse/api-client';

const NETWORK = 'UrbanPulse cannot be reached. Check your connection and try again.';
const TOO_MANY = 'Too many attempts. Wait a few minutes and try again.';

export const UNEXPECTED_ERROR = 'Something went wrong. Try again in a moment.';

/** Messages chosen from the problem `code` and status, never from its text. */
export function signInMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  switch (failure.code) {
    case 'INVALID_CREDENTIALS':
      return 'The email or the password is not correct.';
    case 'ACCOUNT_DISABLED':
      return 'This account is disabled. Contact the city council.';
  }
  return failure.status === 429 ? TOO_MANY : 'Sign-in failed. Try again in a moment.';
}

export function signUpMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  if (failure.code === 'EMAIL_ALREADY_EXISTS') {
    return 'An account with this email already exists. Sign in instead.';
  }
  switch (failure.status) {
    case 400:
    case 422:
      return 'Check your name, a valid email and a password of at least 8 characters.';
    case 429:
      return TOO_MANY;
    default:
      return 'The account could not be created. Try again in a moment.';
  }
}

/** The category is optional, so the message says the report can still be sent. */
export function categoriesMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  return 'The categories could not be loaded. You can report without one or try again.';
}

/** Only for failures without per-field errors: a `422` is shown next to the invalid fields. */
export function reportIncidentMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  if (failure.status === 403) {
    return 'This account cannot report incidents.';
  }
  if (failure.status === 429) {
    return TOO_MANY;
  }
  return 'The incident could not be reported. Try again in a moment.';
}

/** The saved session could not be checked at start-up; the session is kept. */
export function sessionCheckMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  return 'Your session could not be checked. Try again in a moment.';
}
