import type { components, FieldError, FormResult } from '@urbanpulse/api-client';
import { useId, useRef, useState, type FormEvent } from 'react';

import { useCategories, useReloadIncident, useUpdateIncident } from './incident-queries';
import styles from './Incidents.module.css';
import { PRIORITY_LABELS } from './labels';

type Incident = components['schemas']['IncidentResponse'];
type Priority = components['schemas']['Priority'];
type UpdateIncidentRequest = components['schemas']['UpdateIncidentRequest'];

const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH'];

/** Longest justification the API accepts, in characters (`UpdateIncidentRequest`). */
const REASON_MAX_LENGTH = 2000;

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
 * The form keeps what the operator typed whatever the answer: after a `409` they reload the
 * incident and decide again, after a network failure or a `5xx` they save again. It says "saved"
 * only once the API answered, and a second click while saving sends nothing.
 */
export function ClassificationPanel({ incident }: { incident: Incident }) {
  const titleId = useId();
  const categories = useCategories();
  const update = useUpdateIncident(incident.id);
  const reload = useReloadIncident(incident.id);
  const saving = useRef(false);

  const [categoryId, setCategoryId] = useState(incident.category?.id ?? '');
  const [priority, setPriority] = useState<Priority | ''>(incident.priority ?? '');
  const [reason, setReason] = useState(incident.priorityReason ?? '');
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const body = changesOf(incident, categoryId, priority, reason);
  const fieldError = (field: string) =>
    outcome?.kind === 'invalid'
      ? outcome.fieldErrors.find((error) => error.field === field)?.message
      : undefined;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (body === null || saving.current) {
      return;
    }
    saving.current = true;
    setOutcome(null);
    try {
      setOutcome(outcomeOf(await update.mutateAsync(body)));
    } catch {
      setOutcome({ kind: 'failed' });
    } finally {
      saving.current = false;
    }
  }

  // The current category stays a choice even if it is no longer active.
  const options = [...(categories.data ?? [])];
  if (incident.category && !options.some((category) => category.id === incident.category?.id)) {
    options.unshift({ ...incident.category, description: null });
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
          {incident.category === null && <option value="">Not classified</option>}
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
          {incident.priority === null && <option value="">Not set</option>}
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
          maxLength={REASON_MAX_LENGTH}
          onChange={(event) => setReason(event.target.value)}
          aria-invalid={fieldError('priorityReason') ? true : undefined}
          aria-describedby={fieldError('priorityReason') ? `${titleId}-reason` : undefined}
        />
        <FieldMessage id={`${titleId}-reason`} message={fieldError('priorityReason')} />
      </div>
      <OutcomeMessage outcome={outcome} onReload={() => void reload()} />
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

/**
 * The body to send, with only what the operator changed; `null` when nothing changed. A priority
 * always travels with its justification, as the API requires.
 */
function changesOf(
  incident: Incident,
  categoryId: string,
  priority: Priority | '',
  reason: string,
): UpdateIncidentRequest | null {
  const body: UpdateIncidentRequest = { version: incident.version ?? 0 };
  if (categoryId !== '' && categoryId !== incident.category?.id) {
    body.categoryId = categoryId;
  }
  if (
    priority !== '' &&
    (priority !== incident.priority || reason !== (incident.priorityReason ?? ''))
  ) {
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
