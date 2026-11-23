import { useEffect, useId, useRef, useState } from 'react';

import styles from './MultiChoiceField.module.css';

export interface Choice {
  id: string;
  label: string;
  checked: boolean;
}

interface MultiChoiceFieldProps {
  label: string;
  /** What the closed button says about the choice: "All categories", "Parks", "2 categories". */
  summary: string;
  choices: readonly Choice[];
  /** Receives the choice that changed; the caller decides what the new set of choices is. */
  onToggle: (id: string, checked: boolean) => void;
  /** Disables the button, with `summary` as its text (the options are loading or failed). */
  disabled?: boolean;
  /**
   * For a field in a header: the label is written inside the button, before a muted summary, and
   * the list opens towards the left.
   */
  compact?: boolean;
}

/**
 * A button that opens a list of checkboxes, for a filter with several values. A disclosure, not a
 * menu: the list is a group of ordinary checkboxes that the Tab key walks through. It floats over
 * what is below and scrolls inside itself, so the bar that holds it never grows. Escape, a click
 * outside, the button again or the focus leaving the field close it.
 */
export function MultiChoiceField({
  label,
  summary,
  choices,
  onToggle,
  disabled = false,
  compact = false,
}: MultiChoiceFieldProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const listId = useId();
  const shown = open && !disabled;
  // Disabled while open (its options failed to refresh): close it, so it stays closed when enabled
  // again.
  if (disabled && open) {
    setOpen(false);
  }

  useEffect(() => {
    if (!shown) {
      return;
    }
    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [shown]);

  return (
    <div
      ref={rootRef}
      className={styles.field}
      onKeyDown={(event) => {
        if (shown && event.key === 'Escape') {
          setOpen(false);
          buttonRef.current?.focus();
        }
      }}
      // A null `relatedTarget` (Safari does not focus a clicked checkbox) is not leaving: a click
      // outside is closed by its own listener.
      onBlur={(event) => {
        const next = event.relatedTarget;
        if (next instanceof Node && !event.currentTarget.contains(next)) {
          setOpen(false);
        }
      }}
    >
      {!compact && <span className={styles.label}>{label}</span>}
      <button
        ref={buttonRef}
        type="button"
        className={styles.button}
        aria-label={`${label}: ${summary}`}
        aria-expanded={shown}
        aria-controls={shown ? listId : undefined}
        disabled={disabled}
        onClick={() => setOpen(!open)}
      >
        {compact && <span>{label}</span>}
        <span className={compact ? `${styles.summary} ${styles.muted}` : styles.summary}>
          {summary}
        </span>
      </button>
      {shown && (
        <div
          id={listId}
          role="group"
          aria-label={label}
          className={compact ? `${styles.list} ${styles.listEnd}` : styles.list}
        >
          {choices.map((choice) => (
            <label key={choice.id} className={styles.choice}>
              <input
                type="checkbox"
                checked={choice.checked}
                onChange={(event) => onToggle(choice.id, event.target.checked)}
              />
              {choice.label}
            </label>
          ))}
        </div>
      )}
    </div>
  );
}
