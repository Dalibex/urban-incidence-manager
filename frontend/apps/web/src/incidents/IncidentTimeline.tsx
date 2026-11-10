import type { components } from '@urbanpulse/api-client';
import {
  buildTimeline,
  formatInstant,
  statusColor,
  type ClassificationTimelineEntry,
  type ClassificationValueChange,
  type StatusTimelineEntry,
} from '@urbanpulse/ui';
import type { CSSProperties } from 'react';

import { ROLE_LABELS } from '../layout/role-labels';
import { useClassificationChanges, useIncidentHistory } from './incident-queries';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';
import { PRIORITY_LABELS } from './labels';

type Priority = components['schemas']['Priority'];

/**
 * The timeline of an incident for staff (#169): every status change with its actor, internal ones
 * marked, and the classification changes among them. The status changes show as soon as they
 * load; if the classification changes fail, they stay on screen with a way to try again.
 */
export function IncidentTimeline({ incidentId }: { incidentId: string }) {
  const history = useIncidentHistory(incidentId);
  const classification = useClassificationChanges(incidentId);
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
        <>
          <ol className={styles.history}>
            {buildTimeline(history.data, classification.data ?? []).map((entry) =>
              entry.kind === 'status' ? (
                <StatusEntry key={entry.key} entry={entry} />
              ) : (
                <ClassificationEntry key={entry.key} entry={entry} />
              ),
            )}
          </ol>
          {classification.isPending ? (
            <p className={styles.muted} role="status">
              Loading the classification changes…
            </p>
          ) : (
            classification.isError && (
              <LoadFailure
                what="The classification changes"
                error={classification.error}
                retrying={classification.isFetching}
                onRetry={() => void classification.refetch()}
              />
            )
          )}
        </>
      )}
    </section>
  );
}

function StatusEntry({ entry }: { entry: StatusTimelineEntry }) {
  return (
    <li
      className={styles.entry}
      style={{ '--status-color': statusColor(entry.status) } as CSSProperties}
    >
      <p className={styles.entryHeading}>
        <span className={styles.event}>{entry.title}</span>
        <span className={styles.muted}>
          <Instant value={entry.instant} />
        </span>
        {entry.internal && <span className={styles.internal}>Internal</span>}
      </p>
      {entry.actor && (
        <p className={`${styles.reason} ${styles.muted}`}>
          By <span>{entry.actor.name}</span>, {ROLE_LABELS[entry.actor.role].toLowerCase()}
        </p>
      )}
      {entry.reason && <p className={styles.reason}>{entry.reason}</p>}
    </li>
  );
}

function ClassificationEntry({ entry }: { entry: ClassificationTimelineEntry }) {
  return (
    <li className={`${styles.entry} ${styles.classificationEntry}`}>
      <p className={styles.entryHeading}>
        <span className={styles.event}>{entry.title}</span>
        <span className={styles.muted}>
          <Instant value={entry.instant} />
        </span>
        <span className={styles.internal}>Internal</span>
      </p>
      <p className={`${styles.reason} ${styles.muted}`}>
        By <span>{entry.actor.name}</span>
      </p>
      {entry.changes.map((change) => (
        <p key={change.field} className={styles.reason}>
          <ChangedValue change={change} />
        </p>
      ))}
    </li>
  );
}

const FIELD_LABELS: Record<ClassificationValueChange['field'], string> = {
  category: 'Category',
  priority: 'Priority',
  priorityReason: 'Why this priority',
};

const NONE: Record<ClassificationValueChange['field'], string> = {
  category: 'Not classified',
  priority: 'Not set',
  priorityReason: 'None',
};

/** "Priority: High (was Not set)": the new value first, which is what staff look for. */
function ChangedValue({ change }: { change: ClassificationValueChange }) {
  const show = (value: string | null) =>
    value === null
      ? NONE[change.field]
      : change.field === 'priority'
        ? PRIORITY_LABELS[value as Priority]
        : value;
  return (
    <>
      {FIELD_LABELS[change.field]}: {show(change.after)}{' '}
      <span className={styles.muted}>(was {show(change.before)})</span>
    </>
  );
}

function Instant({ value }: { value: string }) {
  return <time dateTime={value}>{formatInstant(value)}</time>;
}
