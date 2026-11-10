import type { components, FieldError, FormResult } from '@urbanpulse/api-client';
import { useId, useRef, useState, type FormEvent } from 'react';

import { useAddComment, useReloadIncident } from './incident-queries';
import styles from './Incidents.module.css';

type Comment = components['schemas']['CommentResponse'];

/**
 * Longest comment the API accepts, in characters (`Comment.MAX_TEXT_LENGTH`). Counted in code
 * points before sending, like the API, so an emoji is one character. The textarea has no
 * `maxLength`: it would count UTF-16 units, and cut a pasted text without saying so.
 */
const COMMENT_MAX_LENGTH = 2000;

const MISSING_TEXT = 'Write the comment.';
const TEXT_TOO_LONG = `The comment can have at most ${COMMENT_MAX_LENGTH} characters.`;

type Outcome =
  | { kind: 'added' }
  | { kind: 'notFound' }
  | { kind: 'invalid'; fieldErrors: FieldError[] }
  | { kind: 'unreachable' }
  | { kind: 'failed' };

/**
 * An internal comment for the other staff of the incident (#50), in every status: the API allows
 * it while the user may see the incident. It ignores a second click while sending and keeps the
 * text whatever the answer, so a failed comment can be sent again; only a saved one clears it.
 */
export function CommentPanel({ incidentId }: { incidentId: string }) {
  const titleId = useId();
  const addComment = useAddComment(incidentId);
  const reload = useReloadIncident(incidentId);
  const inFlight = useRef(false);
  const [text, setText] = useState('');
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const textError =
    outcome?.kind === 'invalid'
      ? outcome.fieldErrors.find((error) => error.field === 'text')?.message
      : undefined;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) {
      return;
    }
    if (text.trim() === '') {
      setOutcome({ kind: 'invalid', fieldErrors: [{ field: 'text', message: MISSING_TEXT }] });
      return;
    }
    if (Array.from(text).length > COMMENT_MAX_LENGTH) {
      setOutcome({ kind: 'invalid', fieldErrors: [{ field: 'text', message: TEXT_TOO_LONG }] });
      return;
    }
    inFlight.current = true;
    setOutcome(null);
    try {
      const result = await addComment.mutateAsync(text);
      if (result.ok) {
        setText('');
        setOutcome({ kind: 'added' });
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
        Comment
      </h3>
      <p className={styles.fieldNote}>For the staff of this incident. The citizen never sees it.</p>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-text-field`}>Comment text</label>
        <textarea
          id={`${titleId}-text-field`}
          value={text}
          rows={3}
          onChange={(event) => setText(event.target.value)}
          aria-required
          aria-invalid={textError ? true : undefined}
          aria-describedby={textError ? `${titleId}-text` : undefined}
        />
        {textError && (
          <span id={`${titleId}-text`} className={styles.fieldError}>
            {textError}
          </span>
        )}
      </div>
      <OutcomeMessage outcome={outcome} onReload={() => void reload()} />
      <p>
        <button type="submit" className={styles.primaryButton} disabled={addComment.isPending}>
          {addComment.isPending ? 'Saving…' : 'Add comment'}
        </button>
      </p>
    </form>
  );
}

function outcomeOf(result: Extract<FormResult<Comment>, { ok: false }>): Outcome {
  const { failure } = result;
  if (failure.kind === 'network') {
    return { kind: 'unreachable' };
  }
  // A technician gets 404 once the incident is no longer assigned to them (docs/security.md).
  if (failure.status === 404) {
    return { kind: 'notFound' };
  }
  if (failure.status === 422 && result.fieldErrors.length > 0) {
    return { kind: 'invalid', fieldErrors: result.fieldErrors };
  }
  return { kind: 'failed' };
}

function OutcomeMessage({ outcome, onReload }: { outcome: Outcome | null; onReload: () => void }) {
  if (outcome === null || outcome.kind === 'added') {
    // Kept in the page while empty, so screen readers announce the message when it appears.
    return (
      <p role="status" className={styles.formMessage}>
        {outcome && 'Comment added.'}
      </p>
    );
  }
  switch (outcome.kind) {
    case 'notFound':
      return (
        <div role="alert" className={`${styles.formMessage} ${styles.formError}`}>
          <p>You can no longer see this incident. Reload it to check.</p>
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
          Check the comment above.
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
          The comment could not be saved. Try again in a moment.
        </p>
      );
  }
}
