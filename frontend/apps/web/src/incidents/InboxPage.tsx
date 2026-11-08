import type { components } from '@urbanpulse/api-client';
import { formatInstant } from '@urbanpulse/ui';
import { useRef } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';

import { incidentPath, type InboxReturn } from '../routing/access';
import { IncidentFilterBar } from './filters/IncidentFilterBar';
import {
  filtersFrom,
  hasFilters,
  withFilters,
  type IncidentFilters,
} from './filters/incident-filters';
import { useIncidentPage } from './incident-queries';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';
import { PRIORITY_LABELS } from './labels';
import { StatusBadge } from './StatusBadge';

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];

const PAGE_PARAM = 'page';

/**
 * The operator's inbox: the incidents that match the filters, newest first, one page at a time
 * (#46, #183). The filters and the page number are in the address (`?status=REPORTED&page=2`, the
 * page starting at 1 for people), so reloading, going back or sharing the link keeps them. Without
 * filters it lists every incident; the menu opens it on the reports waiting for a review.
 */
export function InboxPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = filtersFrom(searchParams);
  const page = pageFrom(searchParams.get(PAGE_PARAM));
  const incidents = useIncidentPage(page - 1, filters);
  const firstFilter = useRef<HTMLInputElement>(null);

  const goTo = (target: number) => {
    const next = new URLSearchParams(searchParams);
    if (target === 1) {
      next.delete(PAGE_PARAM);
    } else {
      next.set(PAGE_PARAM, String(target));
    }
    setSearchParams(next);
  };

  // Other filters give other pages, so a change starts again on the first one.
  const changeFilters = (next: IncidentFilters) => {
    const params = withFilters(searchParams, next);
    params.delete(PAGE_PARAM);
    setSearchParams(params);
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <header className={styles.header}>
        <h1 id="page-title" className={styles.title}>
          Inbox
        </h1>
        <p className={styles.lead}>
          Newest first. Filter the list, then open an incident to review and manage it.
        </p>
      </header>

      <IncidentFilterBar filters={filters} onChange={changeFilters} firstFilterRef={firstFilter} />

      {incidents.isPending ? (
        <p className={styles.state} role="status">
          Loading incidents…
        </p>
      ) : incidents.isError ? (
        <LoadFailure
          what="The incidents"
          error={incidents.error}
          retrying={incidents.isFetching}
          onRetry={() => void incidents.refetch()}
        />
      ) : incidents.data.page.totalElements === 0 && hasFilters(filters) ? (
        <div className={styles.state}>
          <p>No incidents match these filters.</p>
          <p>
            <button
              type="button"
              className={styles.button}
              onClick={() => {
                changeFilters({});
                // This button goes away with the empty state, like the one of the bar is disabled.
                firstFilter.current?.focus();
              }}
            >
              Clear filters
            </button>
          </p>
        </div>
      ) : incidents.data.page.totalElements === 0 ? (
        <div className={styles.state}>
          <p>There are no incidents yet.</p>
          <p className={styles.muted}>
            New reports from citizens appear here as soon as they are sent.
          </p>
        </div>
      ) : incidents.data.content.length === 0 ? (
        <div className={styles.state}>
          <p>Page {page} has no incidents.</p>
          <p>
            <button type="button" className={styles.button} onClick={() => goTo(1)}>
              Go to the first page
            </button>
          </p>
        </div>
      ) : (
        <>
          <IncidentTable rows={incidents.data.content} loading={incidents.isPlaceholderData} />
          <Pagination
            page={page}
            totalPages={incidents.data.page.totalPages}
            totalElements={incidents.data.page.totalElements}
            loading={incidents.isPlaceholderData}
            onGoTo={goTo}
          />
        </>
      )}
    </section>
  );
}

function IncidentTable({ rows, loading }: { rows: IncidentSummary[]; loading: boolean }) {
  // The detail links back to the inbox as the user left it: same filters, same page.
  const inboxReturn: InboxReturn = { inboxSearch: useLocation().search };
  return (
    <div className={styles.tableWrapper}>
      <table className={styles.table} aria-busy={loading}>
        <caption className={styles.visuallyHidden}>Incidents, newest first</caption>
        <thead>
          <tr>
            <th scope="col">Reported</th>
            <th scope="col">Title</th>
            <th scope="col">Category</th>
            <th scope="col">Status</th>
            <th scope="col">Priority</th>
            <th scope="col">
              <span className={styles.visuallyHidden}>Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((incident) => (
            <tr key={incident.id}>
              <td className={styles.date}>
                <time dateTime={incident.reportedAt}>{formatInstant(incident.reportedAt)}</time>
              </td>
              <td className={styles.incidentTitle}>{incident.title}</td>
              <td>
                {incident.category?.name ?? <span className={styles.muted}>Not classified</span>}
              </td>
              <td>
                <StatusBadge status={incident.status} />
              </td>
              <td>
                {incident.priority ? (
                  PRIORITY_LABELS[incident.priority]
                ) : (
                  <span className={styles.muted}>Not set</span>
                )}
              </td>
              <td>
                <Link
                  className={styles.manage}
                  to={incidentPath(incident.id)}
                  state={inboxReturn}
                  aria-label={`Manage ${incident.title}`}
                >
                  Manage
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface PaginationProps {
  page: number;
  totalPages: number;
  totalElements: number;
  loading: boolean;
  onGoTo: (page: number) => void;
}

function Pagination({ page, totalPages, totalElements, loading, onGoTo }: PaginationProps) {
  return (
    <nav className={styles.pagination} aria-label="Pages of the inbox">
      <p className={styles.muted} aria-live="polite">
        Page {page} of {totalPages} ({totalElements}{' '}
        {totalElements === 1 ? 'incident' : 'incidents'})
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
function pageFrom(value: string | null): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page : 1;
}
