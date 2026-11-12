import { ROLES } from '@urbanpulse/domain';
import { useEffect, useRef, useState, type RefObject } from 'react';

import barStyles from '../incidents/filters/IncidentFilterBar.module.css';
import incidentStyles from '../incidents/Incidents.module.css';
import { ROLE_LABELS } from '../layout/role-labels';
import { hasFilters, rolesFrom, withoutEmpty, type UserFilters } from './user-filters';

/** Pause after the last key before a typed text filters the list, in ms. */
const TYPING_PAUSE = 300;

interface UserFilterBarProps {
  filters: UserFilters;
  /** Receives every filter, not only the one that changed; an empty object clears them all. */
  onChange: (filters: UserFilters) => void;
  /** The first filter, so a clear button that becomes disabled keeps a keyboard user in the bar. */
  firstFilterRef: RefObject<HTMLInputElement | null>;
}

/**
 * The filters of the users list (#191): the name and the email, and the roles. A role applies at
 * once; a text applies when the administrator pauses typing or presses Enter. The screen keeps the
 * filters in its address.
 */
export function UserFilterBar({ filters, onChange, firstFilterRef }: UserFilterBarProps) {
  const roles = filters.role ?? [];
  // The filters last sent, which the next change builds on. Two changes before the next render,
  // such as both text pauses ending together, would otherwise each start from the filters of this
  // render, and the second would undo the first (#270). Each render takes the address again.
  const sent = useRef(filters);
  useEffect(() => {
    sent.current = filters;
  });
  const send = (next: UserFilters) => {
    sent.current = next;
    onChange(next);
  };
  const set = (changed: UserFilters) => send(withoutEmpty({ ...sent.current, ...changed }));

  return (
    <form
      className={barStyles.bar}
      role="search"
      aria-label="Filter the users"
      onSubmit={(event) => event.preventDefault()}
    >
      <TextFilter
        label="Name contains"
        applied={filters.name}
        inputRef={firstFilterRef}
        onApply={(name) => set({ name })}
      />
      <TextFilter
        label="Email contains"
        applied={filters.email}
        onApply={(email) => set({ email })}
      />

      <fieldset className={barStyles.statuses}>
        <legend className={barStyles.label}>Roles</legend>
        <div className={barStyles.choices}>
          {ROLES.map((role) => (
            <label key={role} className={barStyles.choice}>
              <input
                type="checkbox"
                checked={roles.includes(role)}
                onChange={(event) =>
                  set({
                    role: rolesFrom(
                      event.target.checked
                        ? [...roles, role]
                        : roles.filter((chosen) => chosen !== role),
                    ),
                  })
                }
              />
              {ROLE_LABELS[role]}
            </label>
          ))}
        </div>
      </fieldset>

      <button
        type="button"
        className={`${incidentStyles.button} ${barStyles.clear}`}
        onClick={() => {
          send({});
          // The button becomes disabled, which sends the focus to the page; the first filter keeps
          // a keyboard user in the bar.
          firstFilterRef.current?.focus();
        }}
        disabled={!hasFilters(filters)}
      >
        Clear filters
      </button>
    </form>
  );
}

interface TextFilterProps {
  label: string;
  /** The text applied to the list, or `undefined` when there is none. */
  applied: string | undefined;
  inputRef?: RefObject<HTMLInputElement | null>;
  /** Receives the trimmed text, or `''` when it is emptied. */
  onApply: (text: string) => void;
}

/**
 * A text that filters after a pause. It keeps what is typed, so the address changing under the
 * keys (the previous pause being applied) never takes characters away; only a change that did not
 * come from here (Clear filters, going back) replaces it.
 */
function TextFilter({ label, applied, inputRef, onApply }: TextFilterProps) {
  const appliedText = applied ?? '';
  const [typed, setTyped] = useState(appliedText);
  const [lastApplied, setLastApplied] = useState(appliedText);
  const [lastSent, setLastSent] = useState(appliedText);
  if (appliedText !== lastApplied) {
    setLastApplied(appliedText);
    if (appliedText !== lastSent) {
      setTyped(appliedText);
    }
  }

  // The latest `onApply`, read when the pause ends: it changes on every render of the screen.
  const apply = useRef(onApply);
  useEffect(() => {
    apply.current = onApply;
  });

  const wanted = typed.trim();
  useEffect(() => {
    if (wanted === appliedText) {
      return;
    }
    const timer = setTimeout(() => {
      setLastSent(wanted);
      apply.current(wanted);
    }, TYPING_PAUSE);
    return () => clearTimeout(timer);
  }, [wanted, appliedText]);

  return (
    <label className={barStyles.field}>
      <span className={barStyles.label}>{label}</span>
      <input
        ref={inputRef}
        type="text"
        value={typed}
        autoComplete="off"
        onChange={(event) => setTyped(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && wanted !== appliedText) {
            setLastSent(wanted);
            onApply(wanted);
          }
        }}
      />
    </label>
  );
}
