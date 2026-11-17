import { formatInstant } from '@urbanpulse/ui';

import { ApiCallError } from '../query-client';
import styles from './Incidents.module.css';

interface LoadFailureProps {
  /** What could not be loaded, as in "The incidents could not be loaded." */
  what: string;
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
}

function isUnreachable(error: unknown): boolean {
  return error instanceof ApiCallError && error.failure.kind === 'network';
}

/** A query that failed, with the reason the user can act on and a way to try again. */
export function LoadFailure({ what, error, retrying, onRetry }: LoadFailureProps) {
  return (
    <div className={`${styles.state} ${styles.failure}`} role="alert">
      <p>
        {isUnreachable(error)
          ? 'The server cannot be reached. Check your connection and try again.'
          : `${what} could not be loaded. Try again in a moment.`}
      </p>
      <p>
        <RetryButton retrying={retrying} onRetry={onRetry} />
      </p>
    </div>
  );
}

interface RefreshFailureProps extends LoadFailureProps {
  /** When the data on screen was read, from the query's `dataUpdatedAt`. */
  shownSince: number;
}

/**
 * A reload of data already on screen that failed (#276), for example when the user comes back to
 * the tab with the server down. It goes above the data, which stays with every form opened on it,
 * and what was typed there, instead of the full-page {@link LoadFailure}.
 */
export function RefreshFailure({
  what,
  error,
  retrying,
  onRetry,
  shownSince,
}: RefreshFailureProps) {
  return (
    <div className={styles.warning} role="alert">
      <p>
        {what} could not be refreshed{isUnreachable(error) ? ': the server cannot be reached' : ''}.
        The page shows what was read at {formatInstant(new Date(shownSince).toISOString())}.
      </p>
      <RetryButton retrying={retrying} onRetry={onRetry} />
    </div>
  );
}

function RetryButton({ retrying, onRetry }: Pick<LoadFailureProps, 'retrying' | 'onRetry'>) {
  return (
    <button type="button" className={styles.button} onClick={onRetry} disabled={retrying}>
      {retrying ? 'Trying again…' : 'Try again'}
    </button>
  );
}
