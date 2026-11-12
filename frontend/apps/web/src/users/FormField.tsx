import type { ReactNode } from 'react';

import styles from '../incidents/Incidents.module.css';
import { FieldMessage } from './user-outcome';
import userStyles from './Users.module.css';

/** What a control needs to point at its label's note and error, and to say it is invalid. */
interface ControlProps {
  id: string;
  'aria-invalid'?: true;
  'aria-describedby'?: string;
}

interface FormFieldProps {
  /** Prefix of the ids of the control and of its messages; unique in the page. */
  id: string;
  label: string;
  /** Help that is always shown, such as the minimum length of a password. */
  note?: string;
  /** The message of the field's error, shown under the control. */
  error: string | undefined;
  children: (control: ControlProps) => ReactNode;
}

/** A labelled control with its help and its error, both read by screen readers with the control. */
export function FormField({ id, label, note, error, children }: FormFieldProps) {
  const describedBy =
    [note ? `${id}-note` : undefined, error ? `${id}-error` : undefined]
      .filter(Boolean)
      .join(' ') || undefined;
  return (
    <div className={`${styles.field} ${userStyles.field}`}>
      <label htmlFor={`${id}-field`}>{label}</label>
      {children({
        id: `${id}-field`,
        ...(error && { 'aria-invalid': true as const }),
        'aria-describedby': describedBy,
      })}
      {note && (
        <span id={`${id}-note`} className={styles.fieldNote}>
          {note}
        </span>
      )}
      <FieldMessage id={`${id}-error`} message={error} />
    </div>
  );
}
