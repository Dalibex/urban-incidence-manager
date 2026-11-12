import type { components, FieldError, FormResult } from '@urbanpulse/api-client';
import { useId, useRef, useState, type FormEvent } from 'react';

import { keepChanged } from './ActionForm';
import { useCategories, useReloadIncident, useUpdateIncident } from './incident-queries';
import styles from './Incidents.module.css';
import { PRIORITY_LABELS } from './labels';

type Incident = components['schemas']['IncidentResponse'];
type Priority = components['schemas']['Priority'];
type UpdateIncidentRequest = components['schemas']['UpdateIncidentRequest'];

const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH'];

/**
 * Longest justification the API accepts, in characters (`UpdateIncidentRequest`). Counted in code
 * points, like the API, so an emoji is one character; the textarea's `maxLength` would count UTF-16
 * units and cut a text with emojis before the real limit.
 */
const REASON_MAX_LENGTH = 2000;
const REASON_TOO_LONG = `The justification can have up to ${REASON_MAX_LENGTH} characters.`;

type Outcome =
  | { kind: 'saved' }
  | { kind: 'changed' }
  | { kind: 'notAllowed' }
  | { kind: 'invalid'; fieldErrors: FieldError[] }
  | { kind: 'unreachable' }
  | { kind: 'failed' };

/**
 * Sets or corrects the category and the priority of the incident, with the justification of the
 * priority (#47, RF10). The validation panel of #48 reuses it before validating.
 *
 * The page reads the incident again while the form is open, for example when the operator comes
 * back to the tab, and the values must never undo a classification changed meanwhile by someone
 * else. So when the classification changed, the form sends the `version` its values were read
 * with: the API answers `409`, and after *Reload the incident* the fields the operator did not
 * change show the incident as it is now, while what they typed stays. A change that leaves the
 * classification as it was, such as an assignment, cannot be undone by them, so the latest
 * `version` goes. After a network failure or a `5xx` they save again. It says "saved" only once
 * the API answered, and a second click while saving sends nothing.
 */
export function ClassificationPanel({ incident }: { incident: Incident }) {
  const titleId = useId();
  const categories = useCategories();
  const update = useUpdateIncident(incident.id);
  const reload = useReloadIncident(incident.id);
  const saving = useRef(false);
  // The incident the values start from.
  const [loaded, setLoaded] = useState(incident);
  const start = valuesOf(loaded);
  const version = sameValues(valuesOf(incident), start) ? incident.version : loaded.version;

  const [categoryId, setCategoryId] = useState(start.categoryId);
  const [priority, setPriority] = useState(start.priority);
  const [reason, setReason] = useState(start.reason);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const body = changesOf(start, version ?? 0, { categoryId, priority, reason });
  const fieldError = (field: string) =>
    outcome?.kind === 'invalid'
      ? outcome.fieldErrors.find((error) => error.field === field)?.message
      : undefined;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (body === null || saving.current) {
      return;
    }
    if (body.priorityReason != null && Array.from(body.priorityReason).length > REASON_MAX_LENGTH) {
      setOutcome({
        kind: 'invalid',
        fieldErrors: [{ field: 'priorityReason', message: REASON_TOO_LONG }],
      });
      return;
    }
    saving.current = true;
    setOutcome(null);
    try {
      const result = await update.mutateAsync(body);
      if (result.ok) {
        startFrom(result.data);
      }
      setOutcome(outcomeOf(result));
    } catch {
      setOutcome({ kind: 'failed' });
    } finally {
      saving.current = false;
    }
  }

  /** Starts the form again from the incident the API answered with after a save. */
  function startFrom(source: Incident) {
    const values = valuesOf(source);
    setLoaded(source);
    setCategoryId(values.categoryId);
    setPriority(values.priority);
    setReason(values.reason);
  }

  async function reloadIncident() {
    const read = await reload();
    // When it could not be read, it is the same incident: what was typed stays for another try.
    if (!read || read.version === loaded.version) {
      return;
    }
    const now = valuesOf(read);
    setLoaded(read);
    setCategoryId((value) => keepChanged(value, start.categoryId, now.categoryId));
    setPriority((value) => keepChanged(value, start.priority, now.priority));
    setReason((value) => keepChanged(value, start.reason, now.reason));
  }

  // The current category stays a choice even if it is no longer active.
  const current = loaded.category;
  const options = [...(categories.data ?? [])];
  if (current && !options.some((category) => category.id === current.id)) {
    options.unshift({ ...current, description: null });
  }

  return (
    <form
      className={styles.form}
      aria-labelledby={titleId}
      onSubmit={(event) => void handleSubmit(event)}
      noValidate
    >
      <h3 id={titleId} className={styles.formTitle}>
        Classification
      </h3>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-category-field`}>Category</label>
        <select
          id={`${titleId}-category-field`}
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
          aria-invalid={fieldError('categoryId') ? true : undefined}
          aria-describedby={fieldError('categoryId') ? `${titleId}-category` : undefined}
        >
          {loaded.category === null && <option value="">Not classified</option>}
          {options.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
        {categories.isError && (
          <span className={styles.fieldNote}>The categories could not be loaded.</span>
        )}
        <FieldMessage id={`${titleId}-category`} message={fieldError('categoryId')} />
      </div>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-priority-field`}>Priority</label>
        <select
          id={`${titleId}-priority-field`}
          value={priority}
          onChange={(event) => setPriority(event.target.value as Priority | '')}
          aria-invalid={fieldError('priority') ? true : undefined}
          aria-describedby={fieldError('priority') ? `${titleId}-priority` : undefined}
        >
          {loaded.priority === null && <option value="">Not set</option>}
          {PRIORITIES.map((value) => (
            <option key={value} value={value}>
              {PRIORITY_LABELS[value]}
            </option>
          ))}
        </select>
        <FieldMessage id={`${titleId}-priority`} message={fieldError('priority')} />
      </div>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-reason-field`}>Why this priority</label>
        <textarea
          id={`${titleId}-reason-field`}
          value={reason}
          rows={3}
          onChange={(event) => setReason(event.target.value)}
          aria-invalid={fieldError('priorityReason') ? true : undefined}
          aria-describedby={fieldError('priorityReason') ? `${titleId}-reason` : undefined}
        />
        <FieldMessage id={`${titleId}-reason`} message={fieldError('priorityReason')} />
      </div>
      <OutcomeMessage outcome={outcome} onReload={() => void reloadIncident()} />
      <p>
        <button
          type="submit"
          className={styles.primaryButton}
          disabled={body === null || update.isPending}
        >
          {update.isPending ? 'Saving…' : 'Save classification'}
        </button>
      </p>
    </form>
  );
}

interface Values {
  categoryId: string;
  priority: Priority | '';
  reason: string;
}

/** The values that show the classification of an incident, or the empty ones when it has none. */
function valuesOf(incident: Incident): Values {
  return {
    categoryId: incident.category?.id ?? '',
    priority: incident.priority ?? '',
    reason: incident.priorityReason ?? '',
  };
}

function sameValues(a: Values, b: Values): boolean {
  return a.categoryId === b.categoryId && a.priority === b.priority && a.reason === b.reason;
}

/**
 * The body to send, with only what the operator changed from the values the form started from,
 * and the `version`; `null` when nothing changed. A priority always travels with its
 * justification, as the API requires.
 */
function changesOf(before: Values, version: number, values: Values): UpdateIncidentRequest | null {
  const { categoryId, priority, reason } = values;
  const body: UpdateIncidentRequest = { version };
  if (categoryId !== '' && categoryId !== before.categoryId) {
    body.categoryId = categoryId;
  }
  if (priority !== '' && (priority !== before.priority || reason !== before.reason)) {
    body.priority = priority;
    body.priorityReason = reason;
  }
  return body.categoryId === undefined && body.priority === undefined ? null : body;
}

function outcomeOf(result: FormResult<Incident>): Outcome {
  if (result.ok) {
    return { kind: 'saved' };
  }
  const { failure } = result;
  if (failure.kind === 'network') {
    return { kind: 'unreachable' };
  }
  if (failure.code === 'CONCURRENT_MODIFICATION') {
    return { kind: 'changed' };
  }
  if (failure.code === 'CLASSIFICATION_NOT_ALLOWED') {
    return { kind: 'notAllowed' };
  }
  if (failure.status === 422 && result.fieldErrors.length > 0) {
    return { kind: 'invalid', fieldErrors: result.fieldErrors };
  }
  return { kind: 'failed' };
}

function OutcomeMessage({ outcome, onReload }: { outcome: Outcome | null; onReload: () => void }) {
  if (outcome === null) {
    // Kept in the page while empty, so screen readers announce the message when it appears.
    return <p role="status" className={styles.formMessage} />;
  }
  switch (outcome.kind) {
    case 'saved':
      return (
        <p role="status" className={styles.formMessage}>
          Classification saved.
        </p>
      );
    case 'changed':
    case 'notAllowed':
      return (
        <div role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          <p>
            {outcome.kind === 'changed'
              ? 'The incident changed since you opened it. Reload it, check the new values and save again.'
              : 'This incident can no longer be classified. Reload it to see its status.'}
          </p>
          <p>
            <button type="button" className={styles.button} onClick={onReload}>
              Reload the incident
            </button>
          </p>
        </div>
      );
    case 'invalid':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          Check the fields marked above.
        </p>
      );
    case 'unreachable':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          The server cannot be reached. Check your connection and save again.
        </p>
      );
    case 'failed':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          The classification could not be saved. Save again in a moment.
        </p>
      );
  }
}

function FieldMessage({ id, message }: { id: string; message: string | undefined }) {
  return message ? (
    <span id={id} className={styles.fieldError}>
      {message}
    </span>
  ) : null;
}
