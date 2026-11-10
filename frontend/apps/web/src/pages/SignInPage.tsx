import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router';

import { signInReturnPath } from '../routing/access';
import { type Credentials, useSession } from '../session/SessionContext';
import styles from './Page.module.css';
import { QuickSignIn } from './QuickSignIn';
import { UNEXPECTED_SIGN_IN_ERROR, signInMessage } from './sign-in-messages';

const ERROR_ID = 'sign-in-error';

export function SignInPage() {
  const { user, expired, signIn } = useSession();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Only a failure of the form marks its fields invalid; a quick sign-in leaves them untouched.
  const [errorFromForm, setErrorFromForm] = useState(false);

  if (user) {
    return <Navigate to={signInReturnPath(location.state)} replace />;
  }

  // The form and the quick sign-in share this path, so one guard stops any second request.
  async function signInWith(credentials: Credentials, source: 'form' | 'quick') {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setError(null);
    setErrorFromForm(source === 'form');
    try {
      const result = await signIn(credentials);
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

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void signInWith({ email: email.trim(), password }, 'form');
  }

  const fieldErrorProps =
    error && errorFromForm ? { 'aria-invalid': true, 'aria-describedby': ERROR_ID } : {};

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
        <form className={styles.form} onSubmit={handleSubmit}>
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
        <QuickSignIn
          onSignIn={(credentials) => void signInWith(credentials, 'quick')}
          disabled={submitting}
        />
      </section>
    </main>
  );
}
