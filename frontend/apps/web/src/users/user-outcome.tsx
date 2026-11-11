import type { FieldError, FormResult } from '@urbanpulse/api-client';

import styles from '../incidents/Incidents.module.css';

/** What a form of this screen shows after its request, as the classification panel does. */
export type Outcome =
  | { kind: 'saved' }
  | { kind: 'invalid'; fieldErrors: FieldError[] }
  | { kind: 'missing' }
  | { kind: 'forbidden' }
  | { kind: 'unreachable' }
  | { kind: 'failed' };

/** Whether the same request may work if it is sent again: the form offers *Try again* only then. */
export function canRetry(outcome: Outcome | null): boolean {
  return outcome?.kind === 'unreachable' || outcome?.kind === 'failed';
}

export const EMAIL_TAKEN = 'An account with this email already exists.';

export function outcomeOf(result: FormResult<unknown>): Outcome {
  if (result.ok) {
    return { kind: 'saved' };
  }
  const { failure } = result;
  if (failure.kind === 'network') {
    return { kind: 'unreachable' };
  }
  if (failure.status === 409 && failure.code === 'EMAIL_ALREADY_EXISTS') {
    return { kind: 'invalid', fieldErrors: [{ field: 'email', message: EMAIL_TAKEN }] };
  }
  if (failure.status === 403) {
    return { kind: 'forbidden' };
  }
  if (failure.status === 404) {
    return { kind: 'missing' };
  }
  if (failure.status === 422 && result.fieldErrors.length > 0) {
    return { kind: 'invalid', fieldErrors: result.fieldErrors };
  }
  return { kind: 'failed' };
}

/** The message of the first error of `field`, if the outcome has one. */
export function fieldErrorOf(outcome: Outcome | null, field: string): string | undefined {
  return outcome?.kind === 'invalid'
    ? outcome.fieldErrors.find((error) => error.field === field)?.message
    : undefined;
}

export function FieldMessage({ id, message }: { id: string; message: string | undefined }) {
  return message ? (
    <span id={id} className={styles.fieldError}>
      {message}
    </span>
  ) : null;
}

interface OutcomeMessageProps {
  outcome: Outcome | null;
  /** Said once the API answered a success, as in "Account created." */
  saved: string;
  /** Said when the API failed, as in "The account could not be created." */
  failed: string;
  /** Said for a `422`; by default it points at the fields marked above the message. */
  invalid?: string;
  /** Sends the same request again; the form keeps what was typed. */
  onRetry: () => void;
  /** The request sent by *Try again* is on its way: the alert stays until its answer arrives. */
  retrying?: boolean;
}

export function OutcomeMessage({
  outcome,
  saved,
  failed,
  invalid,
  onRetry,
  retrying = false,
}: OutcomeMessageProps) {
  if (outcome === null) {
    // Kept in the page while empty, so screen readers announce the message when it appears.
    return <p role="status" className={styles.formMessage} />;
  }
  switch (outcome.kind) {
    case 'saved':
      return (
        <p role="status" className={styles.formMessage}>
          {saved}
        </p>
      );
    case 'invalid':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          {invalid ?? 'Check the fields marked above.'}
        </p>
      );
    case 'missing':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          This account no longer exists. Close this panel and check the list.
        </p>
      );
    case 'forbidden':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          You are no longer allowed to manage users. Sign in again to refresh your access.
        </p>
      );
    case 'unreachable':
    case 'failed':
      return (
        <div role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          <p>
            {outcome.kind === 'unreachable'
              ? 'The server cannot be reached. Check your connection and try again.'
              : `${failed} Try again in a moment.`}
          </p>
          <p>
            <button type="button" className={styles.button} onClick={onRetry} disabled={retrying}>
              {retrying ? 'Trying again…' : 'Try again'}
            </button>
          </p>
        </div>
      );
  }
}
