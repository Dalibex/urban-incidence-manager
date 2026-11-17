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

/**
 * The largest offset of a first row that the API reads: it skips `page × size` rows, and counts
 * them in an `int32`. A larger one answers `500` today, not an empty page (#282).
 */
const MAX_API_OFFSET = 2_147_483_647;

/**
 * The page to ask the API for, starting at 0, to show `page` of the address, starting at 1, with
 * `size` rows per page. A page beyond the largest offset the API reads cannot hold any row, since
 * that needs more than two billion of them: it asks for the last page the API reads instead, which
 * is just as empty, so the screen says that the page has no rows instead of failing (#283 C).
 */
export function apiPageOf(page: number, size: number): number {
  return Math.min(page - 1, Math.floor(MAX_API_OFFSET / size));
}
