import type { IncidentStatus, Role } from '@urbanpulse/domain';

import { historyEventLabel } from './incident-labels';

/**
 * Who appears as the author of a change a citizen sees. The API never sends a citizen the actor of
 * a status change (ADR-0020), so the city council stands for whoever made it.
 */
export const CITY_COUNCIL = 'City council';

/** A status change as the API returns it, staff and citizen alike: staff also get the actor. */
export interface TimelineStatusChange {
  id: string;
  instant: string;
  previousStatus: IncidentStatus | null;
  newStatus: IncidentStatus;
  reason: string | null;
  actor?: { name: string; role: Role } | null;
  visibleToCitizen?: boolean;
}

/** The category and priority of an incident before or after a classification change. */
export interface TimelineClassificationValues {
  category?: { name: string } | null;
  priority?: string | null;
  priorityReason?: string | null;
}

/** A classification change, which only staff read (`listIncidentClassificationChanges`, #47). */
export interface TimelineClassificationChange {
  id: string;
  instant: string;
  actor: { name: string };
  before: TimelineClassificationValues;
  after: TimelineClassificationValues;
}

/** One value of a classification change, with what it was and what it became. */
export interface ClassificationValueChange {
  field: 'category' | 'priority' | 'priorityReason';
  before: string | null;
  after: string | null;
}

interface EntryBase {
  /** Unique across both kinds, for the keys of a list. */
  key: string;
  instant: string;
  title: string;
}

export interface StatusTimelineEntry extends EntryBase {
  kind: 'status';
  /** `null` for the report, the first entry. */
  previousStatus: IncidentStatus | null;
  /** The status the change left the incident in, for its colour. */
  status: IncidentStatus;
  /** `null` when the caller does not receive the actor: a citizen. */
  actor: { name: string; role: Role } | null;
  reason: string | null;
  /** Only staff see internal entries; a citizen never receives them. */
  internal: boolean;
}

export interface ClassificationTimelineEntry extends EntryBase {
  kind: 'classification';
  actor: { name: string };
  /** Only what changed, in the order category, priority, justification. */
  changes: ClassificationValueChange[];
}

/** One event of the timeline. Comments (#50) will be one more kind. */
export type TimelineEntry = StatusTimelineEntry | ClassificationTimelineEntry;

const KIND_ORDER: Record<TimelineEntry['kind'], number> = { status: 0, classification: 1 };

/**
 * The history of an incident as one timeline, oldest first: the status changes and, for staff, the
 * classification changes among them (#169). Entries of the same instant keep one order on every
 * load: status changes first, then by id.
 */
export function buildTimeline(
  statusChanges: readonly TimelineStatusChange[],
  classificationChanges: readonly TimelineClassificationChange[] = [],
): TimelineEntry[] {
  const entries: TimelineEntry[] = [
    ...statusChanges.map(statusEntry),
    ...classificationChanges.map(classificationEntry),
  ];
  return entries.sort(
    (one, other) =>
      Date.parse(one.instant) - Date.parse(other.instant) ||
      KIND_ORDER[one.kind] - KIND_ORDER[other.kind] ||
      (one.key < other.key ? -1 : one.key > other.key ? 1 : 0),
  );
}

function statusEntry(change: TimelineStatusChange): StatusTimelineEntry {
  return {
    kind: 'status',
    key: `status-${change.id}`,
    instant: change.instant,
    title: historyEventLabel(change.previousStatus, change.newStatus),
    previousStatus: change.previousStatus,
    status: change.newStatus,
    actor: change.actor ?? null,
    reason: change.reason,
    internal: change.visibleToCitizen === false,
  };
}

function classificationEntry(change: TimelineClassificationChange): ClassificationTimelineEntry {
  const { before, after } = change;
  const changes: ClassificationValueChange[] = [];
  const category = [before.category?.name ?? null, after.category?.name ?? null] as const;
  if (category[0] !== category[1]) {
    changes.push({ field: 'category', before: category[0], after: category[1] });
  }
  const priority = [before.priority ?? null, after.priority ?? null] as const;
  if (priority[0] !== priority[1]) {
    changes.push({ field: 'priority', before: priority[0], after: priority[1] });
  }
  const reason = [before.priorityReason ?? null, after.priorityReason ?? null] as const;
  if (reason[0] !== reason[1]) {
    changes.push({ field: 'priorityReason', before: reason[0], after: reason[1] });
  }
  return {
    kind: 'classification',
    key: `classification-${change.id}`,
    instant: change.instant,
    title: 'Classification changed',
    actor: change.actor,
    changes,
  };
}
