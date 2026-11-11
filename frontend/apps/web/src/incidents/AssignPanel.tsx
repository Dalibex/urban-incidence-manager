import type { components } from '@urbanpulse/api-client';
import { useId, useRef, useState, type FormEvent } from 'react';

import { outcomeOf, OutcomeMessage, type Outcome, type PanelProps } from './ActionForm';
import {
  useAssignIncident,
  useDepartments,
  useReloadIncident,
  useTechnicians,
} from './incident-queries';
import styles from './Incidents.module.css';

type CreateAssignmentRequest = components['schemas']['CreateAssignmentRequest'];

/** Longest team the API accepts, in characters (`CreateAssignmentRequest`), counted in code points. */
const TEAM_MAX_LENGTH = 100;

/** Operators see every incident, so a 404 means it is no longer there (docs/security.md). */
const NOT_FOUND = 'This incident is no longer available. Reload it to check.';

/**
 * Assigns a `VALIDATED` or `REOPENED` incident to an active department and, optionally, a technician
 * and a team, or reassigns an `ASSIGNED` one (#185, ADR-0020). A reassignment starts from the
 * active assignment and is sent only when something changed.
 *
 * Like the other actions, it sends the `version` of the incident it shows, keeps what the operator
 * chose whatever the answer, says "assigned" only once the API answered, and a second click while
 * sending sends nothing.
 */
export function AssignPanel({ incident, onSending, onDone }: PanelProps) {
  const titleId = useId();
  const departments = useDepartments();
  const technicians = useTechnicians();
  const assign = useAssignIncident(incident.id);
  const reload = useReloadIncident(incident.id);
  const inFlight = useRef(false);
  const current = incident.assignment ?? null;
  const reassigning = incident.status === 'ASSIGNED';

  const [departmentId, setDepartmentId] = useState(current?.department.id ?? '');
  const [technicianId, setTechnicianId] = useState(current?.technician?.id ?? '');
  const [team, setTeam] = useState(current?.team ?? '');
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const unchanged =
    current !== null &&
    departmentId === current.department.id &&
    technicianId === (current.technician?.id ?? '') &&
    team.trim() === (current.team ?? '');
  const fieldError = (field: string) =>
    outcome?.kind === 'invalid'
      ? outcome.fieldErrors.find((error) => error.field === field)?.message
      : undefined;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || unchanged) {
      return;
    }
    const missing = checkFields(departmentId, team);
    if (missing.length > 0) {
      setOutcome({ kind: 'invalid', fieldErrors: missing });
      return;
    }
    const body: CreateAssignmentRequest = { version: incident.version ?? 0, departmentId };
    if (technicianId !== '') {
      body.technicianId = technicianId;
    }
    if (team.trim() !== '') {
      body.team = team.trim();
    }
    inFlight.current = true;
    setOutcome(null);
    onSending();
    try {
      const result = await assign.mutateAsync(body);
      if (result.ok) {
        onDone({
          message: reassigning ? 'Incident reassigned.' : 'Incident assigned.',
          from: incident.status,
        });
      } else {
        setOutcome(outcomeOf(result));
      }
    } catch {
      setOutcome({ kind: 'failed' });
    } finally {
      inFlight.current = false;
    }
  }

  // The current department and technician stay a choice even if they are no longer active.
  const departmentOptions = [...(departments.data ?? [])];
  if (current && !departmentOptions.some((option) => option.id === current.department.id)) {
    departmentOptions.unshift(current.department);
  }
  const technicianOptions = [...(technicians.data ?? [])];
  const currentTechnician = current?.technician;
  if (
    currentTechnician &&
    !technicianOptions.some((option) => option.id === currentTechnician.id)
  ) {
    technicianOptions.unshift(currentTechnician);
  }

  return (
    <form
      className={styles.form}
      aria-labelledby={titleId}
      onSubmit={(event) => void handleSubmit(event)}
      noValidate
    >
      <h3 id={titleId} className={styles.formTitle}>
        {reassigning ? 'Reassign' : 'Assign'}
      </h3>
      <p className={styles.fieldNote}>
        {reassigning
          ? 'Change who takes care of it. The previous assignment is kept in the history.'
          : 'Choose who takes care of it. Without a technician, it stays with the department and is in nobody’s queue.'}
      </p>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-department-field`}>Department</label>
        <select
          id={`${titleId}-department-field`}
          value={departmentId}
          onChange={(event) => setDepartmentId(event.target.value)}
          aria-required
          aria-invalid={fieldError('departmentId') ? true : undefined}
          aria-describedby={fieldError('departmentId') ? `${titleId}-department` : undefined}
        >
          {current === null && <option value="">Choose a department</option>}
          {departmentOptions.map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </select>
        {departments.isError && (
          <span className={styles.fieldNote}>
            The departments could not be loaded. Reload the page to try again.
          </span>
        )}
        <FieldMessage id={`${titleId}-department`} message={fieldError('departmentId')} />
      </div>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-technician-field`}>Technician</label>
        <select
          id={`${titleId}-technician-field`}
          value={technicianId}
          onChange={(event) => setTechnicianId(event.target.value)}
          aria-invalid={fieldError('technicianId') ? true : undefined}
          aria-describedby={fieldError('technicianId') ? `${titleId}-technician` : undefined}
        >
          <option value="">No technician, the department only</option>
          {technicianOptions.map((technician) => (
            <option key={technician.id} value={technician.id}>
              {technician.name}
            </option>
          ))}
        </select>
        {technicians.isError && (
          <span className={styles.fieldNote}>
            The technicians could not be loaded. You can still assign the department only.
          </span>
        )}
        <FieldMessage id={`${titleId}-technician`} message={fieldError('technicianId')} />
      </div>
      <div className={styles.field}>
        <label htmlFor={`${titleId}-team-field`}>Team (optional)</label>
        <input
          id={`${titleId}-team-field`}
          type="text"
          value={team}
          onChange={(event) => setTeam(event.target.value)}
          aria-invalid={fieldError('team') ? true : undefined}
          aria-describedby={fieldError('team') ? `${titleId}-team` : undefined}
        />
        <FieldMessage id={`${titleId}-team`} message={fieldError('team')} />
      </div>
      <OutcomeMessage outcome={outcome} notFound={NOT_FOUND} onReload={() => void reload()} />
      <p>
        <button
          type="submit"
          className={styles.primaryButton}
          disabled={assign.isPending || unchanged}
        >
          {assign.isPending
            ? 'Saving…'
            : reassigning
              ? 'Reassign the incident'
              : 'Assign the incident'}
        </button>
      </p>
    </form>
  );
}

/** What the API would reject, checked before sending anything. */
function checkFields(departmentId: string, team: string) {
  const errors = [];
  if (departmentId === '') {
    errors.push({ field: 'departmentId', message: 'Choose a department.' });
  }
  if (Array.from(team.trim()).length > TEAM_MAX_LENGTH) {
    errors.push({
      field: 'team',
      message: `The team can have up to ${TEAM_MAX_LENGTH} characters.`,
    });
  }
  return errors;
}

function FieldMessage({ id, message }: { id: string; message: string | undefined }) {
  return message ? (
    <span id={id} className={styles.fieldError}>
      {message}
    </span>
  ) : null;
}
