import type { components } from '@urbanpulse/api-client';
import { formatInstant } from '@urbanpulse/ui';
import { useRef, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';

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
import { pageFrom, Pagination } from './Pagination';
import { PRIORITY_LABELS } from './labels';
import { LoadingSkeleton, SkeletonLine } from './Skeleton';
import { StatusBadge } from './StatusBadge';

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];

const PAGE_PARAM = 'page';

/** The columns of the table, shared by the skeleton so it has the same shape. */
const COLUMNS = ['Reported', 'Title', 'Category', 'Status', 'Priority'] as const;

/** Rows drawn while a page loads: enough to fill the screen, never a count the user reads. */
const SKELETON_ROWS = 5;

interface IncidentListPageProps {
  title: string;
  lead: string;
  /** What the screen says when the user has no incidents at all, and what brings them. */
  noIncidents: { message: string; hint: string };
  /** The last cell of each row, such as the link to the detail; without it there is none. */
  rowAction?: (incident: IncidentSummary) => ReactNode;
}

/**
 * A staff screen of incidents: those the user may see that match the filters, newest first, one
 * page at a time (#46, #183). The API decides which ones the user may see, by role, so the inbox
 * and the work queue are this same screen. The filters and the page number are in the address
 * (`?status=REPORTED&page=2`, the page starting at 1 for people), so reloading, going back or
 * sharing the link keeps them.
 */
export function IncidentListPage({ title, lead, noIncidents, rowAction }: IncidentListPageProps) {
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
          {title}
        </h1>
        <p className={styles.lead}>{lead}</p>
      </header>

      <IncidentFilterBar filters={filters} onChange={changeFilters} firstFilterRef={firstFilter} />

      {incidents.isPending ? (
        <LoadingSkeleton what="incidents">
          <IncidentTableSkeleton withAction={rowAction !== undefined} />
        </LoadingSkeleton>
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
          <p>{noIncidents.message}</p>
          <p className={styles.muted}>{noIncidents.hint}</p>
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
          <IncidentTable
            rows={incidents.data.content}
            loading={incidents.isPlaceholderData}
            rowAction={rowAction}
          />
          <Pagination
            label={`Pages of the ${title.toLowerCase()}`}
            noun={['incident', 'incidents']}
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

interface IncidentTableProps {
  rows: IncidentSummary[];
  loading: boolean;
  rowAction: IncidentListPageProps['rowAction'];
}

function IncidentTable({ rows, loading, rowAction }: IncidentTableProps) {
  return (
    <div className={styles.tableWrapper}>
      <table className={styles.table} aria-busy={loading}>
        <caption className={styles.visuallyHidden}>Incidents, newest first</caption>
        <thead>
          <tr>
            {COLUMNS.map((column) => (
              <th key={column} scope="col">
                {column}
              </th>
            ))}
            {rowAction && (
              <th scope="col">
                <span className={styles.visuallyHidden}>Actions</span>
              </th>
            )}
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
              {rowAction && <td>{rowAction(incident)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The table with its columns and lines in place of the rows, while the first page loads. */
function IncidentTableSkeleton({ withAction }: { withAction: boolean }) {
  return (
    <div className={styles.tableWrapper}>
      <table className={styles.table}>
        <thead>
          <tr>
            {COLUMNS.map((column) => (
              <th key={column}>{column}</th>
            ))}
            {withAction && <th />}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: SKELETON_ROWS }, (_, row) => (
            <tr key={row}>
              {COLUMNS.map((column) => (
                <td key={column}>
                  <SkeletonLine width={column === 'Title' ? '90%' : '60%'} />
                </td>
              ))}
              {withAction && (
                <td>
                  <SkeletonLine width="60%" />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
