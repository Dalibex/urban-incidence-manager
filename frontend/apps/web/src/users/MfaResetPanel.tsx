import type { components } from '@urbanpulse/api-client';
import { useEffect, useId, useRef, useState } from 'react';

import styles from '../incidents/Incidents.module.css';
import { FormField } from './FormField';
import { canRetry, OutcomeMessage, outcomeOf, type Outcome } from './user-outcome';
import { useResetUserMfa } from './user-queries';
import userStyles from './Users.module.css';

type User = components['schemas']['UserResponse'];

/**
 * Longest reason the API accepts, in characters (`CreateUserMfaResetRequest`). Counted in code
 * points before sending, like the API; the textarea has no `maxLength`, which would cut a pasted
 * reason without saying so.
 */
const REASON_MAX_LENGTH = 500;

interface MfaResetPanelProps {
  user: User;
  /** The row is the account of the signed-in administrator. */
  own: boolean;
}

/**
 * Removes the second factor of an account whose owner lost the phone (#57, ADR-0021). The reason is
 * required: the audit entry keeps it. The account enrols again at its next sign-in, and its sessions
 * end, so the action asks for a confirmation first.
 */
export function MfaResetPanel({ user, own }: MfaResetPanelProps) {
  const titleId = useId();
  const reset = useResetUserMfa(user.id);
  const sending = useRef(false);
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | undefined>();
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [pending, setPending] = useState(false);
  const resetButton = useRef<HTMLButtonElement>(null);
  const reasonField = useRef<HTMLTextAreaElement>(null);

  // Asking for the confirmation replaces the button that was focused: the reason gets the focus.
  useEffect(() => {
    if (confirming) {
      reasonField.current?.focus();
    }
  }, [confirming]);

  function validReason(): boolean {
    if (reason.trim() === '') {
      setReasonError('Enter the reason, for the audit log.');
    } else if (Array.from(reason).length > REASON_MAX_LENGTH) {
      setReasonError(`The reason can have at most ${REASON_MAX_LENGTH} characters.`);
    } else {
      setReasonError(undefined);
      return true;
    }
    reasonField.current?.focus();
    return false;
  }

  async function send() {
    if (sending.current || !validReason()) {
      return;
    }
    sending.current = true;
    setPending(true);
    // A failure stays on screen while it is tried again, like the other forms of this screen.
    if (!canRetry(outcome)) {
      setOutcome(null);
    }
    try {
      const result = await reset.mutateAsync(reason.trim());
      const answer = outcomeOf(result);
      setOutcome(answer);
      if (answer.kind === 'saved') {
        setConfirming(false);
        setReason('');
        // The form is gone: the button that opened it gets the focus back.
        requestAnimationFrame(() => resetButton.current?.focus());
      }
    } catch {
      setOutcome({ kind: 'failed' });
    } finally {
      sending.current = false;
      setPending(false);
    }
  }

  return (
    <section className={styles.form} aria-labelledby={titleId}>
      <h3 id={titleId} className={styles.formTitle}>
        Second factor
      </h3>
      {confirming ? (
        <div role="group" aria-label="Confirm the reset of the second factor">
          <p>
            Reset the second factor of {user.name}? Their sessions end, and at their next sign-in
            they set it up again with a new QR code.
            {own && ' This is your own account: you will have to sign in again too.'}
          </p>
          <FormField id={`${titleId}-reason`} label="Reason" error={reasonError}>
            {(control) => (
              <textarea
                {...control}
                ref={reasonField}
                value={reason}
                rows={3}
                aria-required
                onChange={(event) => setReason(event.target.value)}
              />
            )}
          </FormField>
          <div className={userStyles.buttons}>
            <button
              type="button"
              className={userStyles.danger}
              disabled={pending}
              onClick={() => void send()}
            >
              {pending ? 'Resetting…' : 'Yes, reset'}
            </button>
            <button
              type="button"
              className={styles.button}
              disabled={pending}
              onClick={() => {
                setConfirming(false);
                setReasonError(undefined);
                setOutcome(null);
                requestAnimationFrame(() => resetButton.current?.focus());
              }}
            >
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <>
          <p className={styles.fieldNote}>For an account whose owner lost the authenticator app.</p>
          <p>
            <button
              ref={resetButton}
              type="button"
              className={styles.button}
              onClick={() => {
                setOutcome(null);
                setConfirming(true);
              }}
            >
              Reset second factor
            </button>
          </p>
        </>
      )}
      <OutcomeMessage
        outcome={outcome}
        saved={`Second factor reset. ${user.name} sets it up again at the next sign-in.`}
        failed="The second factor could not be reset."
        onRetry={() => void send()}
        retrying={pending}
      />
    </section>
  );
}
