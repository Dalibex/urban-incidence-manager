import { changePassword } from '@urbanpulse/api-client';
import { useEffect, useRef, useState, type FormEvent } from 'react';

import { useSession } from '../session/SessionContext';
import styles from './Page.module.css';
import {
  NEW_PASSWORD_MIN_LENGTH,
  UNEXPECTED_PASSWORD_CHANGE_ERROR,
  newPasswordProblem,
  passwordChangeMessage,
} from './password-change-messages';

const ERROR_ID = 'password-change-error';

interface PasswordChangeFormProps {
  email: string;
  /** The password the user just signed in with: it is the credential of the change (#165). */
  currentPassword: string;
  /** The password changed; the user signs in again with the new one. */
  onChanged: () => void;
  onCancel: () => void;
}

/**
 * The step of the sign-in page for an account that must change its password before its first
 * session. It keeps the passwords in memory only, never in the URL or the browser storage.
 */
export function PasswordChangeForm({
  email,
  currentPassword,
  onChanged,
  onCancel,
}: PasswordChangeFormProps) {
  const { api } = useSession();
  const [newPassword, setNewPassword] = useState('');
  const [repeated, setRepeated] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const firstField = useRef<HTMLInputElement>(null);

  // The form replaces the sign-in form, so the focus would be lost on a removed field.
  useEffect(() => {
    firstField.current?.focus();
  }, []);

  function fail(message: string) {
    // A failed attempt never leaves a password on the screen.
    setNewPassword('');
    setRepeated('');
    setError(message);
    firstField.current?.focus();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    const problem = newPasswordProblem(currentPassword, newPassword, repeated);
    if (problem) {
      fail(problem);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await changePassword(api, { email, currentPassword, newPassword });
      if (result.ok) {
        onChanged();
        return;
      }
      fail(passwordChangeMessage(result.failure));
    } catch (unexpected) {
      console.error(unexpected);
      fail(UNEXPECTED_PASSWORD_CHANGE_ERROR);
    } finally {
      setSubmitting(false);
    }
  }

  const fieldErrorProps = error ? { 'aria-invalid': true, 'aria-describedby': ERROR_ID } : {};

  return (
    <main className={styles.standalone}>
      <section className={styles.page} aria-labelledby="page-title">
        <h1 id="page-title" className={styles.title}>
          Choose your password
        </h1>
        <p className={styles.summary}>
          This is your first sign-in as {email}. Choose a password only you know: the one you were
          given stops working.
        </p>
        <form className={styles.form} onSubmit={(event) => void handleSubmit(event)}>
          <p id={ERROR_ID} role="alert" className={styles.error}>
            {error}
          </p>
          {/* Lets password managers save the new password under the right account. */}
          <input type="email" name="email" autoComplete="username" value={email} hidden readOnly />
          <label className={styles.field}>
            <span>New password</span>
            <input
              ref={firstField}
              type="password"
              name="new-password"
              autoComplete="new-password"
              required
              minLength={NEW_PASSWORD_MIN_LENGTH}
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              {...fieldErrorProps}
            />
          </label>
          <label className={styles.field}>
            <span>Repeat the new password</span>
            <input
              type="password"
              name="repeat-new-password"
              autoComplete="new-password"
              required
              value={repeated}
              onChange={(event) => setRepeated(event.target.value)}
              {...fieldErrorProps}
            />
          </label>
          <p className={styles.note}>At least {NEW_PASSWORD_MIN_LENGTH} characters.</p>
          <button type="submit" className={styles.primaryButton} disabled={submitting}>
            {submitting ? 'Changing the password…' : 'Change the password'}
          </button>
          <button
            type="button"
            className={styles.textButton}
            onClick={onCancel}
            disabled={submitting}
          >
            Back to sign in
          </button>
        </form>
      </section>
    </main>
  );
}
