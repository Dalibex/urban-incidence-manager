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

/**
 * Why a photo was not uploaded. The report is saved before any upload, so a failure that may pass
 * on its own says so: the citizen can try again or leave the report without the photo.
 */
export function uploadPhotoMessage(failure: ApiFailure): string {
  if (failure.kind === 'network' || failure.status >= 500) {
    return 'The photo could not be uploaded. Your report is saved without it; try again when you have a better connection.';
  }
  if (failure.code === 'ATTACHMENT_NOT_ALLOWED') {
    return 'Photos can only be added while the report is waiting for review.';
  }
  switch (failure.status) {
    case 403:
      return 'This account cannot add photos.';
    case 404:
      return 'This report was not found. It may have been removed, or it is not one of yours.';
    case 413:
      return 'The photo is too large. Choose another one.';
    case 422:
      return 'This photo cannot be added: it is not a JPEG, PNG or WebP image, or the report already has the maximum number of photos.';
    case 429:
      return TOO_MANY;
    default:
      return 'The photo could not be uploaded. Your report is saved without it.';
  }
}

/** The saved session could not be checked at start-up; the session is kept. */
export function sessionCheckMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  return 'Your session could not be checked. Try again in a moment.';
}
