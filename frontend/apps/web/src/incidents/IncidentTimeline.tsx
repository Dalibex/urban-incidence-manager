import type { components } from '@urbanpulse/api-client';
import {
  buildTimeline,
  formatInstant,
  statusColor,
  type ClassificationTimelineEntry,
  type ClassificationValueChange,
  type CommentTimelineEntry,
  type StatusTimelineEntry,
  type TimelineEntry,
} from '@urbanpulse/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import type { CSSProperties } from 'react';

import { ROLE_LABELS } from '../layout/role-labels';
import {
  useClassificationChanges,
  useIncidentComments,
  useIncidentHistory,
} from './incident-queries';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';
import { PRIORITY_LABELS } from './labels';

type Priority = components['schemas']['Priority'];

/**
 * The timeline of an incident for staff (#169): every status change with its actor, internal ones
 * marked, and the classification changes and internal comments (#50) among them. The status
 * changes show as soon as they load; if the classification changes or the comments fail, the
 * status changes stay on screen with a way to try again.
 */
export function IncidentTimeline({ incidentId }: { incidentId: string }) {
  const history = useIncidentHistory(incidentId);
  const classification = useClassificationChanges(incidentId);
  const comments = useIncidentComments(incidentId);
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
            {buildTimeline(history.data, classification.data ?? [], comments.data ?? []).map(
              (entry) => (
                <Entry key={entry.key} entry={entry} />
              ),
            )}
          </ol>
          <PartState query={classification} what="classification changes" />
          <PartState query={comments} what="comments" />
        </>
      )}
    </section>
  );
}

/** A part of the timeline read on its own: while it loads or after it fails, the rest stays. */
function PartState({ query, what }: { query: UseQueryResult<unknown>; what: string }) {
  if (query.isPending) {
    return (
      <p className={styles.muted} role="status">
        Loading the {what}…
      </p>
    );
  }
  if (query.isError) {
    return (
      <LoadFailure
        what={`The ${what}`}
        error={query.error}
        retrying={query.isFetching}
        onRetry={() => void query.refetch()}
      />
    );
  }
  return null;
}

function Entry({ entry }: { entry: TimelineEntry }) {
  switch (entry.kind) {
    case 'status':
      return <StatusEntry entry={entry} />;
    case 'classification':
      return <ClassificationEntry entry={entry} />;
    case 'comment':
      return <CommentEntry entry={entry} />;
  }
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
      {entry.actor && <ByActor actor={entry.actor} />}
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

/** An internal comment (#50): it changes nothing, so it has the comment mark, not a status. */
function CommentEntry({ entry }: { entry: CommentTimelineEntry }) {
  return (
    <li className={`${styles.entry} ${styles.commentEntry}`}>
      <p className={styles.entryHeading}>
        <span className={styles.event}>{entry.title}</span>
        <span className={styles.muted}>
          <Instant value={entry.instant} />
        </span>
        <span className={styles.internal}>Internal</span>
      </p>
      <ByActor actor={entry.actor} />
      <p className={styles.reason}>{entry.text}</p>
    </li>
  );
}

/** "By Lucía Martín, municipal operator". */
function ByActor({ actor }: { actor: CommentTimelineEntry['actor'] }) {
  return (
    <p className={`${styles.reason} ${styles.muted}`}>
      By <span>{actor.name}</span>, {ROLE_LABELS[actor.role].toLowerCase()}
    </p>
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
