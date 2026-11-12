import type { components } from '@urbanpulse/api-client';
import { useId, useRef, useState, type FormEvent } from 'react';

import {
  keepChanged,
  outcomeOf,
  OutcomeMessage,
  type Outcome,
  type PanelProps,
} from './ActionForm';
import {
  useAssignIncident,
  useDepartments,
  useReloadIncident,
  useTechnicians,
} from './incident-queries';
import styles from './Incidents.module.css';

type Incident = components['schemas']['IncidentResponse'];
type Assignment = components['schemas']['AssignmentResponse'];
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
 * The page reads the incident again while the form is open, for example when the operator comes
 * back to the tab, and the choices must never undo a reassignment made meanwhile by someone else.
 * So when the assignment changed, the form sends the `version` its choices were read with: the
 * API answers `409`, and after *Reload the incident* the fields the operator did not change show
 * the assignment as it is now, while their own choices stay. A change that leaves the assignment
 * as it was, such as a new classification, cannot be undone by them, so the latest `version` goes.
 * After any other failure the choices stay. It says "assigned" only once the API answered, and a
 * second click while sending sends nothing.
 */
export function AssignPanel({ incident, onSending, onDone }: PanelProps) {
  const titleId = useId();
  const departments = useDepartments();
  const technicians = useTechnicians();
  const assign = useAssignIncident(incident.id);
  const reload = useReloadIncident(incident.id);
  const inFlight = useRef(false);
  // The incident the choices start from.
  const [loaded, setLoaded] = useState(incident);
  const current = loaded.assignment ?? null;
  const reassigning = loaded.status === 'ASSIGNED';
  const start = choicesOf(current);
  const version = sameChoices(choicesOf(incident.assignment ?? null), start)
    ? incident.version
    : loaded.version;

  const [departmentId, setDepartmentId] = useState(start.departmentId);
  const [technicianId, setTechnicianId] = useState(start.technicianId);
  const [team, setTeam] = useState(start.team);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const unchanged =
    current !== null && sameChoices({ departmentId, technicianId, team: team.trim() }, start);
  const fieldError = (field: string) =>
    outcome?.kind === 'invalid'
      ? outcome.fieldErrors.find((error) => error.field === field)?.message
      : undefined;

  /** Starts the form again from the incident the API answered with after a save. */
  function startFrom(source: Incident) {
    const choices = choicesOf(source.assignment ?? null);
    setLoaded(source);
    setDepartmentId(choices.departmentId);
    setTechnicianId(choices.technicianId);
    setTeam(choices.team);
  }

  async function reloadIncident() {
    const read = await reload();
    // When it could not be read, it is the same incident: the choices stay for another try.
    if (!read || read.version === loaded.version) {
      return;
    }
    const now = choicesOf(read.assignment ?? null);
    setLoaded(read);
    setDepartmentId((value) => keepChanged(value, start.departmentId, now.departmentId));
    setTechnicianId((value) => keepChanged(value, start.technicianId, now.technicianId));
    setTeam((value) => keepChanged(value, start.team, now.team));
  }

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
    const body: CreateAssignmentRequest = { version: version ?? 0, departmentId };
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
        startFrom(result.data);
        onDone({
          message: reassigning ? 'Incident reassigned.' : 'Incident assigned.',
          from: loaded.status,
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
      <OutcomeMessage
        outcome={outcome}
        notFound={NOT_FOUND}
        onReload={() => void reloadIncident()}
      />
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

interface Choices {
  departmentId: string;
  technicianId: string;
  team: string;
}

/** The choices that show an assignment, or the empty ones when there is none. */
function choicesOf(assignment: Assignment | null): Choices {
  return {
    departmentId: assignment?.department.id ?? '',
    technicianId: assignment?.technician?.id ?? '',
    team: assignment?.team ?? '',
  };
}

function sameChoices(a: Choices, b: Choices): boolean {
  return (
    a.departmentId === b.departmentId && a.technicianId === b.technicianId && a.team === b.team
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
