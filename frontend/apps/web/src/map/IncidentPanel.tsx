import { formatInstant } from '@urbanpulse/ui';
import { useEffect, useRef } from 'react';
import { Link } from 'react-router';

import { useIncident } from '../incidents/incident-queries';
import incidentStyles from '../incidents/Incidents.module.css';
import { PRIORITY_LABELS } from '../incidents/labels';
import { LoadFailure } from '../incidents/LoadFailure';
import { isNotFound } from '../incidents/not-found';
import { StatusBadge } from '../incidents/StatusBadge';
import { incidentPath } from '../routing/access';
import styles from './Map.module.css';

interface IncidentPanelProps {
  incidentId: string;
  onClose: () => void;
}

/**
 * The summary of the incident selected on the map, with *Manage* to its detail (#53). It takes the
 * focus when it opens, so a keyboard user lands on it, and Escape closes it.
 */
export function IncidentPanel({ incidentId, onClose }: IncidentPanelProps) {
  const incident = useIncident(incidentId);
  // The marker may be older than the incident: it can leave the user's scope (closed, or no longer
  // assigned to a technician) before the next refresh of the map removes it.
  const notFound = incident.isError && isNotFound(incident.error);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, [incidentId]);

  return (
    <aside
      className={styles.panel}
      aria-labelledby="incident-panel-title"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          onClose();
        }
      }}
    >
      <header className={styles.panelHeader}>
        <h2 id="incident-panel-title" ref={heading} tabIndex={-1} className={styles.panelTitle}>
          {notFound ? 'Incident not found' : (incident.data?.title ?? 'Selected incident')}
        </h2>
        <button type="button" className={incidentStyles.button} onClick={onClose}>
          Close
        </button>
      </header>

      {incident.isPending ? (
        <p role="status">Loading the incident…</p>
      ) : notFound ? (
        <p>It does not exist, or you cannot see it.</p>
      ) : incident.isError ? (
        <LoadFailure
          what="The incident"
          error={incident.error}
          retrying={incident.isFetching}
          onRetry={() => void incident.refetch()}
        />
      ) : (
        <>
          <dl className={styles.summary}>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={incident.data.status} />
            </dd>
            <dt>Priority</dt>
            <dd>
              {incident.data.priority ? (
                PRIORITY_LABELS[incident.data.priority]
              ) : (
                <span className={incidentStyles.muted}>Not set</span>
              )}
            </dd>
            <dt>Category</dt>
            <dd>
              {incident.data.category?.name ?? (
                <span className={incidentStyles.muted}>Not classified</span>
              )}
            </dd>
            <dt>Reported</dt>
            <dd>
              <time dateTime={incident.data.reportedAt}>
                {formatInstant(incident.data.reportedAt)}
              </time>
            </dd>
          </dl>
          <p className={styles.description}>{incident.data.description}</p>
          <Link
            className={incidentStyles.manage}
            to={incidentPath(incidentId)}
            aria-label={`Manage ${incident.data.title}`}
          >
            Manage
          </Link>
        </>
      )}
    </aside>
  );
}
