import { enrollMfa, type MfaChallenge } from '@urbanpulse/api-client';
import { QRCodeSVG } from 'qrcode.react';
import { useEffect, useRef, useState, type FormEvent } from 'react';

import { useSession } from '../session/SessionContext';
import {
  CODE_LENGTH,
  INCOMPLETE_CODE,
  UNEXPECTED_ENROLLMENT_ERROR,
  UNEXPECTED_MFA_ERROR,
  endsChallenge,
  enrollmentMessage,
  mfaCodeMessage,
} from './mfa-messages';
import styles from './Page.module.css';

const ERROR_ID = 'mfa-error';

/** The setup of an account that has no second factor: loading, failed, or the secret to scan. */
type Enrollment =
  | { state: 'loading' }
  | { state: 'failed'; message: string }
  | { state: 'ready'; otpauthUri: string; secret: string }
  // Not enrolling: the account has a second factor already, so only the code is asked.
  | { state: 'none' };

interface MfaStepProps {
  challenge: MfaChallenge;
  /** The sign-in starts again with the password; `message` says why, or is `null` if the user asked. */
  onRestart: (message: string | null) => void;
}

/**
 * The step of the sign-in page that asks for the code of the authenticator app (ADR-0021), after
 * the password. An account without a second factor first sees its QR code. The challenge stays in
 * this component's props, in memory only. A failed request keeps it, so the user retries without
 * typing the password again; a wrong code keeps it too, until the API spends it.
 */
export function MfaStep({ challenge, onRestart }: MfaStepProps) {
  const { api, verifyMfa } = useSession();
  const [enrollment, setEnrollment] = useState<Enrollment>(
    challenge.enrollmentRequired ? { state: 'loading' } : { state: 'none' },
  );
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const codeField = useRef<HTMLInputElement>(null);
  // Each request replaces the secret on the server, so only one runs at a time.
  const enrolling = useRef(false);

  async function requestEnrollment() {
    if (enrolling.current) {
      return;
    }
    enrolling.current = true;
    try {
      const result = await enrollMfa(api, challenge.challengeToken);
      if (result.ok) {
        setEnrollment({ state: 'ready', ...result.data });
      } else if (endsChallenge(result.failure)) {
        onRestart(enrollmentMessage(result.failure));
      } else if (
        result.failure.kind === 'problem' &&
        result.failure.code === 'MFA_ALREADY_ENROLLED'
      ) {
        setEnrollment({ state: 'none' });
      } else {
        setEnrollment({ state: 'failed', message: enrollmentMessage(result.failure) });
      }
    } catch (unexpected) {
      console.error(unexpected);
      setEnrollment({ state: 'failed', message: UNEXPECTED_ENROLLMENT_ERROR });
    } finally {
      enrolling.current = false;
    }
  }

  // Once per challenge; the guard in `requestEnrollment` also covers StrictMode's second run.
  useEffect(() => {
    if (challenge.enrollmentRequired) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sets state after the answer, not here
      void requestEnrollment();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- starts once, retries are by button
  }, [challenge.challengeToken]);

  const asksForCode = enrollment.state === 'ready' || enrollment.state === 'none';
  // The step replaces the sign-in form, so the focus would be lost on a removed field.
  useEffect(() => {
    if (asksForCode) {
      codeField.current?.focus();
    }
  }, [asksForCode]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    if (code.length !== CODE_LENGTH) {
      setError(INCOMPLETE_CODE);
      codeField.current?.focus();
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await verifyMfa(challenge.challengeToken, code);
      // On success the session changes and the page redirects; only a failure needs a message.
      if (result.ok) {
        return;
      }
      if (endsChallenge(result.failure)) {
        onRestart(mfaCodeMessage(result.failure));
        return;
      }
      setError(mfaCodeMessage(result.failure));
      // A wrong code is typed again; one that never arrived stays for the retry.
      if (result.failure.kind === 'problem' && result.failure.status === 401) {
        setCode('');
      }
      codeField.current?.focus();
    } catch (unexpected) {
      // A bug or a misconfigured server, not a user mistake: log it and let the user retry.
      console.error(unexpected);
      setError(`${UNEXPECTED_MFA_ERROR} Your password is still valid.`);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className={styles.standalone}>
      <section className={styles.page} aria-labelledby="page-title">
        <h1 id="page-title" className={styles.title}>
          {challenge.enrollmentRequired ? 'Set up your second factor' : 'Enter your code'}
        </h1>
        <p className={styles.summary}>
          {challenge.enrollmentRequired
            ? 'Your account needs a second step. Scan the QR code with an authenticator app, then type the code it shows.'
            : 'Type the code your authenticator app shows now.'}
        </p>
        {enrollment.state === 'loading' && (
          <p role="status" className={styles.notice}>
            Preparing the setup…
          </p>
        )}
        {enrollment.state === 'failed' && (
          <div role="alert" className={styles.form}>
            <p className={styles.error}>{enrollment.message}</p>
            <button
              type="button"
              className={styles.secondaryButton}
              onClick={() => {
                setEnrollment({ state: 'loading' });
                void requestEnrollment();
              }}
            >
              Try again
            </button>
          </div>
        )}
        {enrollment.state === 'ready' && (
          <div className={styles.form}>
            <div className={styles.qr}>
              <QRCodeSVG
                value={enrollment.otpauthUri}
                size={176}
                title="QR code to add UrbanPulse to your authenticator app"
              />
            </div>
            <p className={styles.note}>
              Cannot scan it? Type this key into the app instead:{' '}
              <code className={styles.secret}>{enrollment.secret}</code>
            </p>
          </div>
        )}
        {asksForCode && (
          <form className={styles.form} onSubmit={(event) => void handleSubmit(event)}>
            <p id={ERROR_ID} role="alert" className={styles.error}>
              {error}
            </p>
            <label className={styles.field}>
              <span>Code</span>
              <input
                ref={codeField}
                type="text"
                name="code"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={CODE_LENGTH}
                value={code}
                // Whatever is not a digit is dropped, such as the space some apps print in the code.
                onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
                {...(error && { 'aria-invalid': true, 'aria-describedby': ERROR_ID })}
              />
            </label>
            <button type="submit" className={styles.primaryButton} disabled={submitting}>
              {submitting ? 'Verifying…' : 'Verify'}
            </button>
          </form>
        )}
        <p>
          <button
            type="button"
            className={styles.textButton}
            onClick={() => onRestart(null)}
            disabled={submitting}
          >
            Back to sign in
          </button>
        </p>
      </section>
    </main>
  );
}
