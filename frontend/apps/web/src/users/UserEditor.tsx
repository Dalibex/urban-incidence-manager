import type { components } from '@urbanpulse/api-client';
import type { Role } from '@urbanpulse/domain';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';

import styles from '../incidents/Incidents.module.css';
import { ROLE_LABELS } from '../layout/role-labels';
import { FormField } from './FormField';
import { changesOf, NAME_MAX_LENGTH, STAFF_ROLES } from './user-changes';
import { canRetry, fieldErrorOf, OutcomeMessage, outcomeOf, type Outcome } from './user-outcome';
import { useUpdateUser } from './user-queries';
import userStyles from './Users.module.css';

type User = components['schemas']['UserResponse'];

interface UserEditorProps {
  user: User;
  /** The row is the account of the signed-in administrator. */
  own: boolean;
  /** The API answered a change: the list behind this panel is out of date until it reloads. */
  onSaved: () => void;
  onClose: () => void;
}

/**
 * Renames an account, changes its role and deactivates or reactivates it (#191). Each request
 * carries only what the administrator changed compared with the account as this panel last knew it
 * (as loaded, then as the API answered), so it cannot undo a change another administrator made
 * meanwhile. The panel offers only what the API accepts: the own role and account, and the role of a
 * citizen, are shown but disabled; the API checks them again.
 */
export function UserEditor({ user, own, onSaved, onClose }: UserEditorProps) {
  const titleId = useId();
  const update = useUpdateUser(user.id);
  const sending = useRef(false);
  const firstField = useRef<HTMLInputElement>(null);

  const [known, setKnown] = useState(user);
  const [name, setName] = useState(user.name);
  const [role, setRole] = useState<Role>(user.role);
  const [detailsOutcome, setDetailsOutcome] = useState<Outcome | null>(null);
  const [statusOutcome, setStatusOutcome] = useState<Outcome | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState<'details' | 'status' | null>(null);
  const submitting = pending !== null;
  const statusAction = useRef<HTMLButtonElement>(null);
  const keepActive = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    firstField.current?.focus();
  }, []);

  // Asking for the confirmation replaces the button that was focused: the safe answer gets the focus.
  useEffect(() => {
    if (confirming) {
      keepActive.current?.focus();
    }
  }, [confirming]);

  const body = changesOf(known, name, role);
  const citizen = known.role === 'CITIZEN';

  /** Sends `request` once at a time and returns the API's answer, or `null` if one is in flight. */
  async function send(
    request: Parameters<typeof update.mutateAsync>[0],
    kind: 'details' | 'status',
  ) {
    if (sending.current) {
      return null;
    }
    sending.current = true;
    setPending(kind);
    try {
      const result = await update.mutateAsync(request);
      if (result.ok) {
        adopt(result.data);
        onSaved();
      }
      return outcomeOf(result);
    } catch {
      return { kind: 'failed' } satisfies Outcome;
    } finally {
      sending.current = false;
      setPending(null);
    }
  }

  /**
   * Takes the API's answer as the account as it is now. What the administrator left untouched
   * follows the answer, so a change another administrator made meanwhile is not taken for one of
   * theirs and sent back; what they are typing stays.
   */
  function adopt(fresh: User) {
    setName((current) => (current.trim() === known.name ? fresh.name : current));
    setRole((current) => (current === known.role ? fresh.role : current));
    setKnown(fresh);
  }

  async function saveDetails() {
    if (body === null) {
      return;
    }
    if (body.name === '') {
      setDetailsOutcome({
        kind: 'invalid',
        fieldErrors: [{ field: 'name', message: 'Enter the name.' }],
      });
      return;
    }
    // A failure stays on screen while it is tried again: its button keeps the focus until the answer.
    const retried = canRetry(detailsOutcome);
    if (!retried) {
      setDetailsOutcome(null);
    }
    setStatusOutcome(null);
    const outcome = await send(body, 'details');
    if (outcome) {
      setDetailsOutcome(outcome);
      if (retried && outcome.kind === 'saved') {
        firstField.current?.focus();
      }
    }
  }

  async function changeStatus(enabled: boolean) {
    if (!canRetry(statusOutcome)) {
      setStatusOutcome(null);
    }
    setDetailsOutcome(null);
    const outcome = await send({ enabled }, 'status');
    if (outcome) {
      setStatusOutcome(outcome);
      setConfirming(false);
      // The button that was pressed is gone or changed into its opposite: keep the focus on it.
      requestAnimationFrame(() => statusAction.current?.focus());
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void saveDetails();
  }

  const error = (field: string) => fieldErrorOf(detailsOutcome, field);
  const roleNote = own
    ? 'You cannot change your own role.'
    : citizen
      ? 'A citizen account keeps its role.'
      : undefined;

  return (
    <div className={userStyles.editor}>
      <form onSubmit={handleSubmit} aria-labelledby={titleId} noValidate className={styles.form}>
        <h2 id={titleId} className={styles.formTitle}>
          Edit {known.name}
        </h2>
        <div className={userStyles.fields}>
          <FormField id={`${titleId}-name`} label="Name" error={error('name')}>
            {(control) => (
              <input
                {...control}
                ref={firstField}
                type="text"
                value={name}
                maxLength={NAME_MAX_LENGTH}
                autoComplete="off"
                onChange={(event) => setName(event.target.value)}
              />
            )}
          </FormField>
          <FormField id={`${titleId}-role`} label="Role" note={roleNote} error={error('role')}>
            {(control) => (
              <select
                {...control}
                value={role}
                disabled={own || citizen}
                onChange={(event) => setRole(event.target.value as Role)}
              >
                {(citizen ? [known.role] : STAFF_ROLES).map((value) => (
                  <option key={value} value={value}>
                    {ROLE_LABELS[value]}
                  </option>
                ))}
              </select>
            )}
          </FormField>
        </div>
        <OutcomeMessage
          outcome={detailsOutcome}
          saved="Changes saved."
          failed="The changes could not be saved."
          onRetry={() => void saveDetails()}
          retrying={pending === 'details'}
        />
        <div className={userStyles.buttons}>
          <button
            type="submit"
            className={styles.primaryButton}
            disabled={body === null || submitting}
          >
            {pending === 'details' ? 'Saving…' : 'Save changes'}
          </button>
          <button type="button" className={styles.button} onClick={onClose}>
            Close
          </button>
        </div>
      </form>

      <section className={styles.form} aria-label={`Access of ${known.name}`}>
        <h3 className={styles.formTitle}>Access</h3>
        {known.enabled ? (
          confirming ? (
            <div role="group" aria-label="Confirm the deactivation">
              <p>
                Deactivate {known.name}? Their sessions end within minutes, when they next refresh
                them, and they cannot sign in until you reactivate the account.
              </p>
              <div className={userStyles.buttons}>
                <button
                  type="button"
                  className={userStyles.danger}
                  disabled={submitting}
                  onClick={() => void changeStatus(false)}
                >
                  Yes, deactivate
                </button>
                <button
                  ref={keepActive}
                  type="button"
                  className={styles.button}
                  disabled={submitting}
                  onClick={() => {
                    setConfirming(false);
                    requestAnimationFrame(() => statusAction.current?.focus());
                  }}
                >
                  Keep it active
                </button>
              </div>
            </div>
          ) : (
            <>
              <p>
                <button
                  ref={statusAction}
                  type="button"
                  className={styles.button}
                  disabled={own || submitting}
                  aria-describedby={own ? `${titleId}-own` : undefined}
                  onClick={() => setConfirming(true)}
                >
                  Deactivate account
                </button>
              </p>
              {own && (
                <p id={`${titleId}-own`} className={styles.fieldNote}>
                  You cannot deactivate your own account.
                </p>
              )}
            </>
          )
        ) : (
          <>
            <p className={styles.fieldNote}>This account is deactivated and cannot sign in.</p>
            <p>
              <button
                ref={statusAction}
                type="button"
                className={styles.button}
                disabled={submitting}
                onClick={() => void changeStatus(true)}
              >
                Reactivate account
              </button>
            </p>
          </>
        )}
        <OutcomeMessage
          outcome={statusOutcome}
          saved={known.enabled ? 'Account reactivated.' : 'Account deactivated.'}
          failed="The account could not be changed."
          invalid={
            statusOutcome?.kind === 'invalid'
              ? (statusOutcome.fieldErrors[0]?.message ?? undefined)
              : undefined
          }
          onRetry={() => void changeStatus(!known.enabled)}
          retrying={pending === 'status'}
        />
      </section>
    </div>
  );
}
