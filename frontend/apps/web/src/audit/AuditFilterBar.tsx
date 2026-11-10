import type { RefObject } from 'react';

import { DayField } from '../incidents/filters/IncidentFilterBar';
import barStyles from '../incidents/filters/IncidentFilterBar.module.css';
import incidentStyles from '../incidents/Incidents.module.css';
import { actionsFrom, hasFilters, withoutEmpty, type AuditFilters } from './audit-filters';
import { ACTION_LABELS, AUDIT_ACTIONS } from './audit-labels';
import styles from './AuditLog.module.css';

interface AuditFilterBarProps {
  filters: AuditFilters;
  /** Receives every filter, not only the one that changed; an empty object clears them all. */
  onChange: (filters: AuditFilters) => void;
  /** Name of the actor filtered by, or `undefined` when no row on screen tells it. */
  actorName: string | undefined;
  /** The first filter, so a clear button that goes away can keep a keyboard user in the bar. */
  firstFilterRef: RefObject<HTMLInputElement | null>;
}

/**
 * The filters of the audit log (#41): actions and days, and the actor chosen from a row. A change
 * applies at once; the screen keeps the filters in its address.
 */
export function AuditFilterBar({
  filters,
  onChange,
  actorName,
  firstFilterRef,
}: AuditFilterBarProps) {
  const actions = filters.action ?? [];
  const set = (changed: AuditFilters) => onChange(withoutEmpty({ ...filters, ...changed }));

  // Each day keeps the range valid: a first day after the last one moves the last one, and back.
  const setFrom = (day: string) => {
    const last = filters.to;
    set({ from: day, to: day && last && day > last ? day : last });
  };
  const setTo = (day: string) => {
    const first = filters.from;
    set({ from: day && first && day < first ? day : first, to: day });
  };

  return (
    <form
      className={barStyles.bar}
      role="search"
      aria-label="Filter the audit log"
      onSubmit={(event) => event.preventDefault()}
    >
      <fieldset className={barStyles.statuses}>
        <legend className={barStyles.label}>Action</legend>
        <div className={barStyles.choices}>
          {AUDIT_ACTIONS.map((action, index) => (
            <label key={action} className={barStyles.choice}>
              <input
                type="checkbox"
                ref={index === 0 ? firstFilterRef : undefined}
                checked={actions.includes(action)}
                onChange={(event) =>
                  set({
                    action: actionsFrom(
                      event.target.checked
                        ? [...actions, action]
                        : actions.filter((chosen) => chosen !== action),
                    ),
                  })
                }
              />
              {ACTION_LABELS[action]}
            </label>
          ))}
        </div>
      </fieldset>

      <DayField label="From" day={filters.from} max={filters.to} onChange={setFrom} />
      <DayField label="To" day={filters.to} min={filters.from} onChange={setTo} />

      {filters.actorId && (
        <div className={barStyles.field}>
          <span className={barStyles.label}>Actor</span>
          <button
            type="button"
            className={`${incidentStyles.button} ${styles.chip}`}
            onClick={() => set({ actorId: undefined })}
            aria-label={`Remove the actor filter: ${actorName ?? 'the chosen user'}`}
          >
            <span className={styles.chipText}>{actorName ?? 'The chosen user'}</span>
            <span aria-hidden="true">✕</span>
          </button>
        </div>
      )}

      <button
        type="button"
        className={`${incidentStyles.button} ${barStyles.clear}`}
        onClick={() => {
          onChange({});
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
