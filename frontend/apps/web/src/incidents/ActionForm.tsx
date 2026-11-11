import type { components, FieldError, FormResult } from '@urbanpulse/api-client';
import { useId, useRef, useState, type FormEvent } from 'react';

import { useReloadIncident } from './incident-queries';
import styles from './Incidents.module.css';

type Incident = components['schemas']['IncidentResponse'];

/** Longest reason the API accepts, in characters (`IncidentReasonRequest`). */
const NOTES_MAX_LENGTH = 2000;

/** An action the API accepted: the message to announce, and the status the incident left. */
export interface ActionDone {
  message: string;
  from: Incident['status'];
}

/** How a panel tells the parent about its action, which outlives the panel's form. */
export interface ActionCallbacks {
  /** Called when the action is sent, so the message of an earlier one goes away. */
  onSending: () => void;
  /** Called once the API accepted the change. */
  onDone: (done: ActionDone) => void;
}

/** What every lifecycle panel receives: the incident, to send its `version` back. */
export interface PanelProps extends ActionCallbacks {
  incident: Incident;
}

/** What the API answered to a lifecycle action, as the form shows it. */
export type Outcome =
  | { kind: 'changed' }
  | { kind: 'notAllowed' }
  | { kind: 'notFound' }
  | { kind: 'invalid'; fieldErrors: FieldError[] }
  | { kind: 'unreachable' }
  | { kind: 'failed' };

interface ActionFormProps extends ActionCallbacks {
  incident: Incident;
  title: string;
  lead: string;
  submitLabel: string;
  done: string;
  /** What a `404` means for this role, followed by the reload button. */
  notFound: string;
  /** Required notes, sent as `reason`; without it the action sends only the `version`. */
  notes?: { label: string; missing: string };
  sending: boolean;
  send: (version: number, notes: string) => Promise<FormResult<Incident>>;
}

/**
 * One lifecycle action, of the technician or of the operator. It sends the `version` of the incident it shows, keeps
 * the notes whatever the answer, and ignores a second click while sending. On success the incident
 * changes status, so this form gives way to the next one and the parent announces `done` and moves
 * the focus.
 */
export function ActionForm({
  incident,
  title,
  lead,
  submitLabel,
  done,
  notFound,
  notes,
  sending,
  send,
  onSending,
  onDone,
}: ActionFormProps) {
  const titleId = useId();
  const reload = useReloadIncident(incident.id);
  const inFlight = useRef(false);
  const [text, setText] = useState('');
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const notesError =
    outcome?.kind === 'invalid'
      ? outcome.fieldErrors.find((error) => error.field === 'reason')?.message
      : undefined;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) {
      return;
    }
    if (notes && text.trim() === '') {
      setOutcome({ kind: 'invalid', fieldErrors: [{ field: 'reason', message: notes.missing }] });
      return;
    }
    inFlight.current = true;
    setOutcome(null);
    onSending();
    try {
      const result = await send(incident.version ?? 0, text);
      if (result.ok) {
        onDone({ message: done, from: incident.status });
      } else {
        setOutcome(outcomeOf(result));
      }
    } catch {
      setOutcome({ kind: 'failed' });
    } finally {
      inFlight.current = false;
    }
  }

  return (
    <form
      className={styles.form}
      aria-labelledby={titleId}
      onSubmit={(event) => void handleSubmit(event)}
      noValidate
    >
      <h3 id={titleId} className={styles.formTitle}>
        {title}
      </h3>
      <p className={styles.fieldNote}>{lead}</p>
      {notes && (
        <div className={styles.field}>
          <label htmlFor={`${titleId}-notes-field`}>{notes.label}</label>
          <textarea
            id={`${titleId}-notes-field`}
            value={text}
            rows={4}
            maxLength={NOTES_MAX_LENGTH}
            onChange={(event) => setText(event.target.value)}
            aria-required
            aria-invalid={notesError ? true : undefined}
            aria-describedby={notesError ? `${titleId}-notes` : undefined}
          />
          {notesError && (
            <span id={`${titleId}-notes`} className={styles.fieldError}>
              {notesError}
            </span>
          )}
        </div>
      )}
      <OutcomeMessage outcome={outcome} notFound={notFound} onReload={() => void reload()} />
      <p>
        <button type="submit" className={styles.primaryButton} disabled={sending}>
          {sending ? 'Saving…' : submitLabel}
        </button>
      </p>
    </form>
  );
}

/** The outcome of an answer the API did not accept. */
export function outcomeOf(result: Extract<FormResult<Incident>, { ok: false }>): Outcome {
  const { failure } = result;
  if (failure.kind === 'network') {
    return { kind: 'unreachable' };
  }
  if (failure.code === 'CONCURRENT_MODIFICATION') {
    return { kind: 'changed' };
  }
  if (failure.code === 'INVALID_TRANSITION') {
    return { kind: 'notAllowed' };
  }
  // What a 404 means depends on the role, so the panel gives its message (`notFound`).
  if (failure.status === 404) {
    return { kind: 'notFound' };
  }
  if (failure.status === 422 && result.fieldErrors.length > 0) {
    return { kind: 'invalid', fieldErrors: result.fieldErrors };
  }
  return { kind: 'failed' };
}

const RELOAD_MESSAGES = {
  changed: 'The incident changed since you opened it. Reload it and check it before trying again.',
  notAllowed: 'This incident no longer allows this action. Reload it to see its status.',
} as const;

export interface OutcomeMessageProps {
  outcome: Outcome | null;
  notFound: string;
  onReload: () => void;
}

/**
 * The answer of an action next to its button: a reload after a `409` or a `404`, and an error with
 * the form kept for a retry otherwise. The assignment form shows it too.
 */
export function OutcomeMessage({ outcome, notFound, onReload }: OutcomeMessageProps) {
  if (outcome === null) {
    // Kept in the page while empty, so screen readers announce the message when it appears.
    return <p role="status" className={styles.formMessage} />;
  }
  switch (outcome.kind) {
    case 'changed':
    case 'notAllowed':
    case 'notFound':
      return (
        <div role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          <p>{outcome.kind === 'notFound' ? notFound : RELOAD_MESSAGES[outcome.kind]}</p>
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
          The server cannot be reached. Check your connection and try again.
        </p>
      );
    case 'failed':
      return (
        <p role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          The change could not be saved. Try again in a moment.
        </p>
      );
  }
}
