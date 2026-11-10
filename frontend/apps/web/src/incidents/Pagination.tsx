import styles from './Incidents.module.css';

interface PaginationProps {
  /** Accessible name of the navigation, as in "Pages of the inbox". */
  label: string;
  /** What the list counts, singular and plural, as in `['incident', 'incidents']`. */
  noun: readonly [string, string];
  page: number;
  totalPages: number;
  totalElements: number;
  loading: boolean;
  onGoTo: (page: number) => void;
}

/** Previous and next page of a paginated list; `page` starts at 1, as people count. */
export function Pagination({
  label,
  noun,
  page,
  totalPages,
  totalElements,
  loading,
  onGoTo,
}: PaginationProps) {
  return (
    <nav className={styles.pagination} aria-label={label}>
      <p className={styles.muted} aria-live="polite">
        Page {page} of {totalPages} ({totalElements} {totalElements === 1 ? noun[0] : noun[1]})
      </p>
      <div className={styles.pageButtons}>
        <button
          type="button"
          className={styles.button}
          onClick={() => onGoTo(page - 1)}
          disabled={loading || page <= 1}
        >
          Previous page
        </button>
        <button
          type="button"
          className={styles.button}
          onClick={() => onGoTo(page + 1)}
          disabled={loading || page >= totalPages}
        >
          Next page
        </button>
      </div>
    </nav>
  );
}

/** The page of the address, starting at 1; anything else shows the first page. */
export function pageFrom(value: string | null): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page : 1;
}
