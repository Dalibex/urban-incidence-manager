import type { Credentials } from '../session/SessionContext';
import styles from './Page.module.css';
import { QUICK_SIGN_IN_USERS, quickSignInConfig } from './quick-sign-in';

interface QuickSignInProps {
  onSignIn: (credentials: Credentials) => void;
  disabled: boolean;
}

/** One button per demo user, for the demo and for development. Nothing renders with the flag off. */
export function QuickSignIn({ onSignIn, disabled }: QuickSignInProps) {
  const config = quickSignInConfig();
  if (config.state === 'off') {
    return null;
  }
  return (
    <section className={styles.quickSignIn} aria-labelledby="quick-sign-in-title">
      <h2 id="quick-sign-in-title" className={styles.subtitle}>
        Quick sign-in (demo)
      </h2>
      {config.state === 'missing-password' ? (
        <p className={styles.note}>
          Set <code>VITE_DEMO_PASSWORD</code> in <code>apps/web/.env</code> to use quick sign-in.
        </p>
      ) : (
        <div className={styles.buttonRow}>
          {QUICK_SIGN_IN_USERS.map(({ role, label, email }) => (
            <button
              key={role}
              type="button"
              className={styles.secondaryButton}
              disabled={disabled}
              onClick={() => onSignIn({ email, password: config.password })}
            >
              {label}
            </button>
          ))}
        </div>
      )}
    </section>
  );
}
