import { INCIDENT_STATUSES, isIncidentStatus } from '@urbanpulse/domain';
import { STATUS_LABELS } from '@urbanpulse/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { useRef, useState, type ReactNode } from 'react';

import { useCategories, useDepartments } from '../incident-queries';
import incidentStyles from '../Incidents.module.css';
import { PRIORITY_LABELS } from '../labels';
import styles from './IncidentFilterBar.module.css';
import {
  hasFilters,
  isDay,
  isPriority,
  PRIORITIES,
  withoutEmpty,
  type IncidentFilters,
} from './incident-filters';

interface IncidentFilterBarProps {
  filters: IncidentFilters;
  /** Receives every filter, not only the one that changed; an empty object clears them all. */
  onChange: (filters: IncidentFilters) => void;
}

/**
 * The staff filters of an incident list (#58): status, category, priority, department and the days
 * it was reported. A change applies at once. The screen keeps the filters (the inbox keeps them in
 * its address), so the bar holds no state of its own.
 */
export function IncidentFilterBar({ filters, onChange }: IncidentFilterBarProps) {
  const categories = useCategories();
  const departments = useDepartments();
  const firstFilter = useRef<HTMLSelectElement>(null);

  const set = (changed: IncidentFilters) => onChange(withoutEmpty({ ...filters, ...changed }));

  // Each day keeps the range valid: a first day after the last one moves the last one, and back.
  const setFirstDay = (day: string) => {
    const last = filters.reportedTo;
    set({ reportedFrom: day, reportedTo: day && last && day > last ? day : last });
  };
  const setLastDay = (day: string) => {
    const first = filters.reportedFrom;
    set({ reportedFrom: day && first && day < first ? day : first, reportedTo: day });
  };

  return (
    <form
      className={styles.bar}
      role="search"
      aria-label="Filter incidents"
      onSubmit={(event) => event.preventDefault()}
    >
      <Field label="Status">
        <select
          ref={firstFilter}
          value={filters.status ?? ''}
          onChange={(event) => {
            const status = event.target.value;
            set({ status: isIncidentStatus(status) ? status : undefined });
          }}
        >
          <option value="">All statuses</option>
          {INCIDENT_STATUSES.map((status) => (
            <option key={status} value={status}>
              {STATUS_LABELS[status]}
            </option>
          ))}
        </select>
      </Field>

      <OptionsField
        label="Category"
        plural="categories"
        query={categories}
        value={filters.categoryId}
        onChange={(categoryId) => set({ categoryId })}
      />

      <Field label="Priority">
        <select
          value={filters.priority ?? ''}
          onChange={(event) => {
            const priority = event.target.value;
            set({ priority: isPriority(priority) ? priority : undefined });
          }}
        >
          <option value="">All priorities</option>
          {PRIORITIES.map((priority) => (
            <option key={priority} value={priority}>
              {PRIORITY_LABELS[priority]}
            </option>
          ))}
        </select>
      </Field>

      <OptionsField
        label="Department"
        plural="departments"
        query={departments}
        value={filters.departmentId}
        onChange={(departmentId) => set({ departmentId })}
      />

      <DayField
        label="Reported from"
        day={filters.reportedFrom}
        max={filters.reportedTo}
        onChange={setFirstDay}
      />

      <DayField
        label="Reported to"
        day={filters.reportedTo}
        min={filters.reportedFrom}
        onChange={setLastDay}
      />

      <button
        type="button"
        className={`${incidentStyles.button} ${styles.clear}`}
        onClick={() => {
          onChange({});
          // The button becomes disabled, which sends the focus to the page; the first filter keeps
          // a keyboard user in the bar (#78, T1).
          firstFilter.current?.focus();
        }}
        disabled={!hasFilters(filters)}
      >
        Clear filters
      </button>
    </form>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className={styles.field}>
      <span className={styles.label}>{label}</span>
      {children}
    </label>
  );
}

interface DayFieldProps {
  label: string;
  /** The day applied to the list, `YYYY-MM-DD`. */
  day: string | undefined;
  min?: string;
  max?: string;
  /** Receives a real day, or `''` when the field is emptied. */
  onChange: (day: string) => void;
}

/**
 * A day of the range. While a day is typed, the browser reports a complete date as soon as the year
 * has one digit (30/10/2 is `0002-10-30`), and the address drops it. So the field keeps what is
 * typed and applies it only once it is a real day, or empty (#78, T1).
 */
function DayField({ label, day, min, max, onChange }: DayFieldProps) {
  const applied = day ?? '';
  const [typed, setTyped] = useState(applied);
  const [lastApplied, setLastApplied] = useState(applied);
  // A change from outside (clear, the other end of the range, the address) replaces what is typed.
  if (applied !== lastApplied) {
    setLastApplied(applied);
    setTyped(applied);
  }

  return (
    <Field label={label}>
      <input
        type="date"
        value={typed}
        min={min}
        max={max}
        onChange={(event) => {
          const value = event.target.value;
          setTyped(value);
          if (value !== applied && (value === '' || isDay(value))) {
            onChange(value);
          }
        }}
      />
    </Field>
  );
}

interface Option {
  id: string;
  name: string;
}

interface OptionsFieldProps {
  label: string;
  /** Lower-case plural of the label, for "All categories". */
  plural: string;
  query: UseQueryResult<Option[]>;
  value: string | undefined;
  onChange: (id: string | undefined) => void;
}

/**
 * A filter whose options come from the API. Until they arrive, or when they cannot be loaded, the
 * choice is disabled and the rest of the bar keeps working. A chosen id that is not among the
 * options (an inactive category, an old link) stays chosen and visible, so the list never filters
 * by something the user cannot see.
 */
function OptionsField({ label, plural, query, value, onChange }: OptionsFieldProps) {
  const options = query.data ?? [];
  const missing = value !== undefined && !options.some((option) => option.id === value);
  const noun = label.toLowerCase();
  const placeholder = query.isPending
    ? `Loading ${plural}…`
    : query.isError
      ? `The ${plural} could not be loaded`
      : `All ${plural}`;

  return (
    <Field label={label}>
      <select
        value={value ?? ''}
        disabled={!query.isSuccess}
        onChange={(event) => onChange(event.target.value || undefined)}
      >
        <option value="">{placeholder}</option>
        {missing && (
          <option value={value}>{query.isSuccess ? `Unknown ${noun}` : `Chosen ${noun}`}</option>
        )}
        {options.map((option) => (
          <option key={option.id} value={option.id}>
            {option.name}
          </option>
        ))}
      </select>
    </Field>
  );
}
