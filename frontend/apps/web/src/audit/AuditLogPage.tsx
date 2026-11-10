import type { components } from '@urbanpulse/api-client';
import { formatInstant } from '@urbanpulse/ui';
import { useRef } from 'react';
import { Link, useSearchParams } from 'react-router';

import styles from '../incidents/Incidents.module.css';
import { LoadFailure } from '../incidents/LoadFailure';
import { pageFrom, Pagination } from '../incidents/Pagination';
import { incidentPath } from '../routing/access';
import { filtersFrom, hasFilters, withFilters, type AuditFilters } from './audit-filters';
import { ACTION_LABELS, fieldChanges, formatAuditValue } from './audit-labels';
import { useAuditEntryPage } from './audit-queries';
import { AuditFilterBar } from './AuditFilterBar';
import auditStyles from './AuditLog.module.css';

type AuditEntry = components['schemas']['AuditEntryResponse'];

const PAGE_PARAM = 'page';

/**
 * The administrator's audit log (#41, RF18): who did what and when, newest first, one page at a
 * time. It only reads: the trail cannot be changed. The filters and the page are in the address,
 * so reloading or going back keeps them.
 */
export function AuditLogPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = filtersFrom(searchParams);
  const page = pageFrom(searchParams.get(PAGE_PARAM));
  const entries = useAuditEntryPage(page - 1, filters);
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
  const changeFilters = (next: AuditFilters) => {
    const params = withFilters(searchParams, next);
    params.delete(PAGE_PARAM);
    setSearchParams(params);
  };

  // The address keeps only the actor's id; the rows filtered by them carry their name.
  const actorName = entries.data?.content.find((entry) => entry.actor.id === filters.actorId)?.actor
    .name;

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <header className={styles.header}>
        <h1 id="page-title" className={styles.title}>
          Audit log
        </h1>
        <p className={styles.lead}>
          Who did what and when, newest first. Entries cannot be changed or deleted.
        </p>
      </header>

      <AuditFilterBar
        filters={filters}
        onChange={changeFilters}
        actorName={actorName}
        firstFilterRef={firstFilter}
      />

      {entries.isPending ? (
        <p className={styles.state} role="status">
          Loading the audit log…
        </p>
      ) : entries.isError ? (
        <LoadFailure
          what="The audit log"
          error={entries.error}
          retrying={entries.isFetching}
          onRetry={() => void entries.refetch()}
        />
      ) : entries.data.page.totalElements === 0 && hasFilters(filters) ? (
        <div className={styles.state}>
          <p>No entries match these filters.</p>
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
      ) : entries.data.page.totalElements === 0 ? (
        <div className={styles.state}>
          <p>The audit log is empty.</p>
          <p className={styles.muted}>
            Administrative actions and classifications appear here once someone makes them.
          </p>
        </div>
      ) : entries.data.content.length === 0 ? (
        <div className={styles.state}>
          <p>Page {page} has no entries.</p>
          <p>
            <button type="button" className={styles.button} onClick={() => goTo(1)}>
              Go to the first page
            </button>
          </p>
        </div>
      ) : (
        <>
          <AuditTable
            rows={entries.data.content}
            loading={entries.isPlaceholderData}
            onActor={(actorId) => changeFilters({ ...filters, actorId })}
          />
          <Pagination
            label="Pages of the audit log"
            noun={['entry', 'entries']}
            page={page}
            totalPages={entries.data.page.totalPages}
            totalElements={entries.data.page.totalElements}
            loading={entries.isPlaceholderData}
            onGoTo={goTo}
          />
        </>
      )}
    </section>
  );
}

interface AuditTableProps {
  rows: AuditEntry[];
  loading: boolean;
  onActor: (actorId: string) => void;
}

function AuditTable({ rows, loading, onActor }: AuditTableProps) {
  return (
    <div className={styles.tableWrapper}>
      <table className={styles.table} aria-busy={loading}>
        <caption className={styles.visuallyHidden}>Audit entries, newest first</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Actor</th>
            <th scope="col">Action</th>
            <th scope="col">Record</th>
            <th scope="col">Changes</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((entry) => (
            <tr key={entry.id}>
              <td className={styles.date}>
                <time dateTime={entry.instant}>{formatInstant(entry.instant)}</time>
              </td>
              <td>
                <button
                  type="button"
                  className={auditStyles.actor}
                  onClick={() => onActor(entry.actor.id)}
                  aria-label={`Show only the entries of ${entry.actor.name}`}
                >
                  {entry.actor.name}
                </button>
              </td>
              <td className={auditStyles.action}>{ACTION_LABELS[entry.action]}</td>
              <td>
                <AuditTargetCell target={entry.target} />
              </td>
              <td>
                <AuditChanges details={entry.details} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const TARGET_KINDS: Record<components['schemas']['AuditTargetType'], string> = {
  USER: 'User',
  INCIDENT: 'Incident',
};

function AuditTargetCell({ target }: { target: AuditEntry['target'] }) {
  if (target === null) {
    return <span className={styles.muted}>None</span>;
  }
  // A record that no longer exists has no label: say so instead of leaving the cell blank.
  const label = target.label ?? <span className={styles.muted}>No longer exists</span>;
  return (
    <>
      <span className={auditStyles.kind}>{TARGET_KINDS[target.type]}</span>
      {target.type === 'INCIDENT' && target.label !== null ? (
        <Link className={styles.link} to={incidentPath(target.id)}>
          {label}
        </Link>
      ) : (
        label
      )}
    </>
  );
}

function AuditChanges({ details }: { details: AuditEntry['details'] }) {
  const changes = details ? fieldChanges(details) : [];
  if (changes.length === 0) {
    return <span className={styles.muted}>No details</span>;
  }
  return (
    <ul className={auditStyles.changes}>
      {changes.map(({ field, before, after }) => (
        <li key={field}>
          <span className={auditStyles.field}>{field}:</span>{' '}
          {before !== undefined && (
            <>
              {formatAuditValue(before)} <span aria-label="changed to">→</span>{' '}
            </>
          )}
          {after !== undefined ? (
            formatAuditValue(after)
          ) : (
            <span className={styles.muted}>removed</span>
          )}
        </li>
      ))}
    </ul>
  );
}
