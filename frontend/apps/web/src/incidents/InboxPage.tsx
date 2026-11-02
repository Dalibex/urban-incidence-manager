import type { components } from '@urbanpulse/api-client';
import { Link, useSearchParams } from 'react-router';

import { incidentPath } from '../routing/access';
import { useIncidentPage } from './incident-queries';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';
import { PRIORITY_LABELS, formatInstant } from './labels';
import { StatusBadge } from './StatusBadge';

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];

const PAGE_PARAM = 'page';

/**
 * The operator's inbox: every incident, newest first, one page at a time (#46). The page number is
 * in the address (`?page=2`, starting at 1 for people), so reloading or going back keeps it.
 */
export function InboxPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const page = pageFrom(searchParams.get(PAGE_PARAM));
  const incidents = useIncidentPage(page - 1);

  const goTo = (target: number) => {
    setSearchParams(target === 1 ? {} : { [PAGE_PARAM]: String(target) });
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <header className={styles.header}>
        <h1 id="page-title" className={styles.title}>
          Inbox
        </h1>
        <p className={styles.lead}>
          Every incident, newest first. Open one to review and manage it.
        </p>
      </header>

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
