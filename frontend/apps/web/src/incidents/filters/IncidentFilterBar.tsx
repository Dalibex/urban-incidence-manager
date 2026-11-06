import { INCIDENT_STATUSES, isIncidentStatus } from '@urbanpulse/domain';
import { STATUS_LABELS } from '@urbanpulse/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { useCategories, useDepartments } from '../incident-queries';
import incidentStyles from '../Incidents.module.css';
import { PRIORITY_LABELS } from '../labels';
import styles from './IncidentFilterBar.module.css';
import {
  hasFilters,
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

      <Field label="Reported from">
        <input
          type="date"
          value={filters.reportedFrom ?? ''}
          max={filters.reportedTo}
          onChange={(event) => setFirstDay(event.target.value)}
        />
      </Field>

      <Field label="Reported to">
        <input
          type="date"
          value={filters.reportedTo ?? ''}
          min={filters.reportedFrom}
          onChange={(event) => setLastDay(event.target.value)}
        />
      </Field>

      <button
        type="button"
        className={`${incidentStyles.button} ${styles.clear}`}
        onClick={() => onChange({})}
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
