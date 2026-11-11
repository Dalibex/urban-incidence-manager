import type { components } from '@urbanpulse/api-client';
import type { Role } from '@urbanpulse/domain';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';

import styles from '../incidents/Incidents.module.css';
import { ROLE_LABELS } from '../layout/role-labels';
import { FormField } from './FormField';
import { EMAIL_MAX_LENGTH, NAME_MAX_LENGTH, passwordTooLong, STAFF_ROLES } from './user-changes';
import { canRetry, fieldErrorOf, OutcomeMessage, outcomeOf, type Outcome } from './user-outcome';
import { useCreateUser } from './user-queries';
import userStyles from './Users.module.css';

type CreateUserRequest = components['schemas']['CreateUserRequest'];

/** Shortest initial password the API accepts, as in `createRegistration`. */
export const PASSWORD_MIN_LENGTH = 8;

interface CreateUserFormProps {
  onClose: () => void;
}

/**
 * Creates a staff account (#191). The form keeps what was typed whatever the answer: after a
 * network failure or a `5xx` the administrator sends it again. It says "created" only once the API
 * answered, and a second click while sending creates nothing more (#63, T18 and T19).
 */
export function CreateUserForm({ onClose }: CreateUserFormProps) {
  const titleId = useId();
  const create = useCreateUser();
  const sending = useRef(false);
  const firstField = useRef<HTMLInputElement>(null);

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role | ''>('');
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // The form appears on a click, so the keyboard user starts typing in it.
  useEffect(() => {
    firstField.current?.focus();
  }, []);

  async function send() {
    if (sending.current) {
      return;
    }
    const mistakes = mistakesIn(name, email, password, role);
    if (mistakes.length > 0) {
      setOutcome({ kind: 'invalid', fieldErrors: mistakes });
      return;
    }
    const body: CreateUserRequest = {
      name: name.trim(),
      email: email.trim(),
      password,
      role: role as Role,
    };
    sending.current = true;
    setSubmitting(true);
    // A failure stays on screen while it is tried again: its button keeps the focus until the answer.
    if (!canRetry(outcome)) {
      setOutcome(null);
    }
    try {
      const result = outcomeOf(await create.mutateAsync(body));
      setOutcome(result);
      if (result.kind === 'saved') {
        setName('');
        setEmail('');
        setPassword('');
        setRole('');
        firstField.current?.focus();
      }
    } catch {
      setOutcome({ kind: 'failed' });
    } finally {
      sending.current = false;
      setSubmitting(false);
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void send();
  }

  const error = (field: string) => fieldErrorOf(outcome, field);

  return (
    <form
      className={`${styles.form} ${userStyles.panel}`}
      aria-labelledby={titleId}
      onSubmit={handleSubmit}
      noValidate
    >
      <h2 id={titleId} className={styles.formTitle}>
        New staff account
      </h2>
      <p className={styles.fieldNote}>
        The person must choose their own password the first time they sign in.
      </p>
      <div className={userStyles.fields}>
        <FormField id={`${titleId}-name`} label="Name" error={error('name')}>
          {(control) => (
            <input
              {...control}
              ref={firstField}
              type="text"
              value={name}
              maxLength={NAME_MAX_LENGTH}
              readOnly={submitting}
              autoComplete="off"
              onChange={(event) => setName(event.target.value)}
            />
          )}
        </FormField>
        <FormField id={`${titleId}-email`} label="Email" error={error('email')}>
          {(control) => (
            <input
              {...control}
              type="email"
              value={email}
              maxLength={EMAIL_MAX_LENGTH}
              readOnly={submitting}
              autoComplete="off"
              onChange={(event) => setEmail(event.target.value)}
            />
          )}
        </FormField>
        <FormField
          id={`${titleId}-password`}
          label="Initial password"
          note={`At least ${PASSWORD_MIN_LENGTH} characters.`}
          error={error('password')}
        >
          {(control) => (
            <input
              {...control}
              type="password"
              value={password}
              readOnly={submitting}
              autoComplete="new-password"
              onChange={(event) => setPassword(event.target.value)}
            />
          )}
        </FormField>
        <FormField id={`${titleId}-role`} label="Role" error={error('role')}>
          {(control) => (
            <select
              {...control}
              value={role}
              disabled={submitting}
              onChange={(event) => setRole(event.target.value as Role | '')}
            >
              <option value="">Choose a role</option>
              {STAFF_ROLES.map((value) => (
                <option key={value} value={value}>
                  {ROLE_LABELS[value]}
                </option>
              ))}
            </select>
          )}
        </FormField>
      </div>
      <OutcomeMessage
        outcome={outcome}
        saved="Account created. Share the initial password with the person in a safe way."
        failed="The account could not be created."
        onRetry={() => void send()}
        retrying={submitting}
      />
      <div className={userStyles.buttons}>
        <button type="submit" className={styles.primaryButton} disabled={submitting}>
          {submitting ? 'Creating…' : 'Create account'}
        </button>
        <button type="button" className={styles.button} onClick={onClose}>
          Close
        </button>
      </div>
    </form>
  );
}

/** What is wrong before sending, so an empty field does not cost a request. */
function mistakesIn(name: string, email: string, password: string, role: Role | '') {
  const mistakes: { field: string; message: string }[] = [];
  if (name.trim() === '') {
    mistakes.push({ field: 'name', message: 'Enter the name.' });
  }
  if (email.trim() === '') {
    mistakes.push({ field: 'email', message: 'Enter the email.' });
  }
  if (password.length < PASSWORD_MIN_LENGTH) {
    mistakes.push({
      field: 'password',
      message: `The password needs at least ${PASSWORD_MIN_LENGTH} characters.`,
    });
  } else if (passwordTooLong(password)) {
    mistakes.push({ field: 'password', message: 'The password is too long. Use a shorter one.' });
  }
  if (role === '') {
    mistakes.push({ field: 'role', message: 'Choose a role.' });
  }
  return mistakes;
}
