import { ApiCallError } from '../query-client';
import styles from './Incidents.module.css';

interface LoadFailureProps {
  /** What could not be loaded, as in "The incidents could not be loaded." */
  what: string;
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
}

/** A query that failed, with the reason the user can act on and a way to try again. */
export function LoadFailure({ what, error, retrying, onRetry }: LoadFailureProps) {
  const unreachable = error instanceof ApiCallError && error.failure.kind === 'network';
  return (
    <div className={`${styles.state} ${styles.failure}`} role="alert">
      <p>
        {unreachable
          ? 'The server cannot be reached. Check your connection and try again.'
          : `${what} could not be loaded. Try again in a moment.`}
      </p>
      <p>
        <button type="button" className={styles.button} onClick={onRetry} disabled={retrying}>
          {retrying ? 'Trying again…' : 'Try again'}
        </button>
      </p>
    </div>
  );
}
