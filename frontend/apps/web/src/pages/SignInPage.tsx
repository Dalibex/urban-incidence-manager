import { useState, type FormEvent } from 'react';
import { Navigate } from 'react-router';

import { PATHS } from '../routing/access';
import { useSession } from '../session/SessionContext';
import styles from './Page.module.css';
import { UNEXPECTED_SIGN_IN_ERROR, signInMessage } from './sign-in-messages';

const ERROR_ID = 'sign-in-error';

export function SignInPage() {
  const { user, expired, signIn } = useSession();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (user) {
    return <Navigate to={PATHS.home} replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await signIn({ email: email.trim(), password });
      // On success the session changes and this page redirects; only a failure needs a message.
      if (!result.ok) {
        setError(signInMessage(result.failure));
      }
    } catch (unexpected) {
      // A bug or a misconfigured server, not a user mistake: log it and let the user retry.
      console.error(unexpected);
      setError(UNEXPECTED_SIGN_IN_ERROR);
    } finally {
      setSubmitting(false);
    }
  }

  const fieldErrorProps = error ? { 'aria-invalid': true, 'aria-describedby': ERROR_ID } : {};

  return (
    <main className={styles.standalone}>
      <section className={styles.page} aria-labelledby="page-title">
        <h1 id="page-title" className={styles.title}>
          Sign in to UrbanPulse
        </h1>
        <p className={styles.summary}>For municipal staff. Citizens use the mobile app.</p>
        {expired && !error && (
          <p className={styles.notice}>Your session has expired. Sign in again.</p>
        )}
        <form className={styles.form} onSubmit={(event) => void handleSubmit(event)}>
          {/* Always in the page, so screen readers announce the message when it appears. */}
          <p id={ERROR_ID} role="alert" className={styles.error}>
            {error}
          </p>
          <label className={styles.field}>
            <span>Email</span>
            <input
              type="email"
              name="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              {...fieldErrorProps}
            />
          </label>
          <label className={styles.field}>
            <span>Password</span>
            <input
              type="password"
              name="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              {...fieldErrorProps}
            />
          </label>
          <button type="submit" className={styles.primaryButton} disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </section>
    </main>
  );
}
