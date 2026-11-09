import type { components } from '@urbanpulse/api-client';
import { formatInstant, historyEventLabel, NOT_AVAILABLE, statusColor } from '@urbanpulse/ui';
import type { CSSProperties, ReactNode } from 'react';
import { Link, useLocation, useParams } from 'react-router';

import { ROLE_LABELS } from '../layout/role-labels';
import { ApiCallError } from '../query-client';
import { incidentListOf, listReturnPath, type IncidentList } from '../routing/access';
import { useSession } from '../session/SessionContext';
import { useIncident, useIncidentHistory } from './incident-queries';
import { IncidentActions } from './IncidentActions';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';
import { PRIORITY_LABELS } from './labels';
import { StatusBadge } from './StatusBadge';

type Incident = components['schemas']['IncidentResponse'];
type IncidentLocation = components['schemas']['IncidentLocation'];
type StatusChange = components['schemas']['StatusChangeResponse'];

const BACK_LABELS: Record<IncidentList, string> = {
  inbox: 'Back to the inbox',
  workQueue: 'Back to the work queue',
};

/**
 * Everything about one incident, and the place where the lifecycle actions are added (#46). An
 * operator opens it from the inbox and a technician from the work queue (#187), and it leads back
 * to that list.
 */
export function IncidentDetailPage() {
  const { incidentId = '' } = useParams();
  const incident = useIncident(incidentId);
  const { state } = useLocation();
  const list = incidentListOf(useSession().user?.role);

  return (
    <article className={styles.page} aria-labelledby="page-title">
      <Link className={styles.backLink} to={listReturnPath(state, list)}>
        {BACK_LABELS[list]}
      </Link>
      {incident.isPending ? (
        <p className={styles.state} role="status">
          Loading the incident…
        </p>
      ) : incident.isError ? (
        isNotFound(incident.error) ? (
          <div className={styles.state}>
            <h1 id="page-title" className={styles.title}>
              Incident not found
            </h1>
            <p>It does not exist, or you cannot see it.</p>
          </div>
        ) : (
          <LoadFailure
            what="The incident"
            error={incident.error}
            retrying={incident.isFetching}
            onRetry={() => void incident.refetch()}
          />
        )
      ) : (
        <IncidentDetail incident={incident.data} />
      )}
    </article>
  );
}

function IncidentDetail({ incident }: { incident: Incident }) {
  return (
    <>
      <header className={styles.detailHeader}>
        <h1 id="page-title" className={styles.title}>
          {incident.title}
        </h1>
        <StatusBadge status={incident.status} />
      </header>
      <div className={styles.detail}>
        <IncidentActions incident={incident} />
        <div>
          <ReportPanel incident={incident} />
          <LocationPanel location={incident.location} />
          <HistoryPanel incidentId={incident.id} />
        </div>
      </div>
    </>
  );
}

function ReportPanel({ incident }: { incident: Incident }) {
  return (
    <section className={styles.panel} aria-labelledby="report-title">
      <h2 id="report-title" className={styles.panelTitle}>
        Report
      </h2>
      <p className={styles.description}>{incident.description}</p>
      <dl className={styles.facts}>
        <Fact label="Category">
          {incident.category?.name ?? <span className={styles.muted}>Not classified</span>}
        </Fact>
        <Fact label="Priority">
          {incident.priority ? (
            PRIORITY_LABELS[incident.priority]
          ) : (
            <span className={styles.muted}>Not set</span>
          )}
        </Fact>
        {incident.priorityReason && (
          <Fact label="Why this priority">{incident.priorityReason}</Fact>
        )}
        <Fact label="Reported">
          <Instant value={incident.reportedAt} />
        </Fact>
        <Fact label="Last change">
          <Instant value={incident.updatedAt} />
        </Fact>
        {incident.closedAt && (
          <Fact label="Closed">
            <Instant value={incident.closedAt} />
          </Fact>
        )}
      </dl>
    </section>
  );
}

const COORDINATE = new Intl.NumberFormat('en-GB', {
  minimumFractionDigits: 5,
  maximumFractionDigits: 5,
});

const METRES = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 });

function LocationPanel({ location }: { location: IncidentLocation }) {
  return (
    <section className={styles.panel} aria-labelledby="location-title">
      <h2 id="location-title" className={styles.panelTitle}>
        Location
      </h2>
      <dl className={styles.facts}>
        <Fact label="Address">
          <Known value={location.address} />
        </Fact>
        <Fact label="Neighbourhood">
          <Known value={location.neighborhood} />
        </Fact>
        <Fact label="District">
          <Known value={location.district} />
        </Fact>
        <Fact label="Latitude, longitude">
          {COORDINATE.format(location.latitude)}, {COORDINATE.format(location.longitude)}
        </Fact>
        <Fact label="Accuracy">
          <Known
            value={
              location.accuracy === null ? null : `within ${METRES.format(location.accuracy)} m`
            }
          />
        </Fact>
      </dl>
    </section>
  );
}

function HistoryPanel({ incidentId }: { incidentId: string }) {
  const history = useIncidentHistory(incidentId);
  return (
    <section className={styles.panel} aria-labelledby="history-title">
      <h2 id="history-title" className={styles.panelTitle}>
        History
      </h2>
      {history.isPending ? (
        <p className={styles.muted} role="status">
          Loading the history…
        </p>
      ) : history.isError ? (
        <LoadFailure
          what="The history"
          error={history.error}
          retrying={history.isFetching}
          onRetry={() => void history.refetch()}
        />
      ) : (
        <ol className={styles.history}>
          {history.data.map((change) => (
            <HistoryEntry key={change.id} change={change} />
          ))}
        </ol>
      )}
    </section>
  );
}

function HistoryEntry({ change }: { change: StatusChange }) {
  return (
    <li
      className={styles.entry}
      style={{ '--status-color': statusColor(change.newStatus) } as CSSProperties}
    >
      <p className={styles.entryHeading}>
        <span className={styles.event}>
          {historyEventLabel(change.previousStatus, change.newStatus)}
        </span>
        <span className={styles.muted}>
          <Instant value={change.instant} />
        </span>
        {change.visibleToCitizen === false && <span className={styles.internal}>Internal</span>}
      </p>
      {change.actor && (
        <p className={`${styles.reason} ${styles.muted}`}>
          By <span>{change.actor.name}</span>, {ROLE_LABELS[change.actor.role].toLowerCase()}
        </p>
      )}
      {change.reason && <p className={styles.reason}>{change.reason}</p>}
    </li>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function Known({ value }: { value: string | null }) {
  return value === null ? <span className={styles.muted}>{NOT_AVAILABLE}</span> : <>{value}</>;
}

function Instant({ value }: { value: string }) {
  return <time dateTime={value}>{formatInstant(value)}</time>;
}

/** A `404`, or a `400` for an address whose id is not a valid UUID: no incident has it either. */
function isNotFound(error: unknown): boolean {
  return (
    error instanceof ApiCallError &&
    error.failure.kind === 'problem' &&
    (error.failure.status === 404 || error.failure.status === 400)
  );
}
