import type { ApiFailure } from '@urbanpulse/api-client';

export const UNEXPECTED_MFA_ERROR = 'The code could not be checked. Try again in a moment.';
export const UNEXPECTED_ENROLLMENT_ERROR = 'The setup could not be started. Try again in a moment.';
export const CODE_LENGTH = 6;
export const INCOMPLETE_CODE = `Enter the ${CODE_LENGTH} digits the app shows.`;

const NETWORK = 'UrbanPulse cannot be reached. Check your connection and try again.';

/**
 * Whether the sign-in must start again with the password: the challenge is unknown, expired or
 * spent, or its account was disabled, so no code can answer it.
 */
export function endsChallenge(failure: ApiFailure): boolean {
  return (
    failure.kind === 'problem' &&
    ((failure.status === 401 && failure.code === 'INVALID_MFA_CHALLENGE') ||
      failure.code === 'ACCOUNT_DISABLED')
  );
}

/** Message for a failed code, chosen from the problem `code` and status, never its text. */
export function mfaCodeMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return `${NETWORK} Your password is still valid: press Verify to send the code again.`;
  }
  switch (failure.code) {
    case 'INVALID_MFA_CODE':
      return 'That code is not correct. Check the app and type the one it shows now.';
    case 'INVALID_MFA_CHALLENGE':
      return 'Your sign-in has expired. Enter your password again.';
    case 'ACCOUNT_DISABLED':
      return 'This account is disabled. Ask an administrator to enable it.';
  }
  switch (failure.status) {
    case 400:
    case 422:
      return INCOMPLETE_CODE;
    case 429:
      return 'Too many wrong codes. Wait a few minutes and try again.';
    default:
      return `${UNEXPECTED_MFA_ERROR} Your password is still valid.`;
  }
}

/** Message for a failed enrolment; a lost challenge gets the one of {@link mfaCodeMessage}. */
export function enrollmentMessage(failure: ApiFailure): string {
  if (failure.kind === 'network') {
    return NETWORK;
  }
  return failure.code === 'INVALID_MFA_CHALLENGE'
    ? mfaCodeMessage(failure)
    : UNEXPECTED_ENROLLMENT_ERROR;
}
