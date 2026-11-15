import type { components } from '@urbanpulse/api-client';
import {
  availableTransitions,
  OPEN_STATUSES,
  type Role,
  type TransitionActor,
  type TransitionName,
} from '@urbanpulse/domain';
import { useEffect, useRef, useState, type ComponentType } from 'react';

import { OPERATOR_ROLES } from '../routing/access';
import { useSession } from '../session/SessionContext';
import { AssignPanel } from './AssignPanel';
import { ClassificationPanel } from './ClassificationPanel';
import { CommentPanel } from './CommentPanel';
import styles from './Incidents.module.css';
import { ClosePanel, ReopenPanel } from './OperatorActions';
import { RejectPanel, RequestInformationPanel, ValidatePanel } from './ReportActions';
import type { ActionCallbacks, ActionDone, PanelProps } from './ActionForm';
import { AcceptPanel, ResolvePanel } from './TechnicianActions';

type Incident = components['schemas']['IncidentResponse'];

/** Shared with the skeleton of the detail, so the panel keeps its title while the incident loads. */
export const ACTIONS_TITLE = 'Actions';

/**
 * The panel of each transition of ADR-0020, in the order the detail shows them. The detail shows
 * the panels of the transitions that `availableTransitions` (`@urbanpulse/domain`) allows, so a
 * change of the table there changes the panels here; a new transition does not compile until it
 * has its panel.
 */
const TRANSITION_PANELS: Record<TransitionName, ComponentType<PanelProps>> = {
  // The operator classifies the report and then decides on it (#189).
  VALIDATION: ValidatePanel,
  INFORMATION_REQUEST: RequestInformationPanel,
  REJECTION: RejectPanel,
  // To a department and, optionally, a technician; an assigned incident is reassigned (#185).
  ASSIGNMENT: AssignPanel,
  ACCEPTANCE: AcceptPanel,
  RESOLUTION: ResolvePanel,
  // The operator checks the technician's work: close it, or reopen it with a reason (#52).
  CLOSURE: ClosePanel,
  REOPENING: ReopenPanel,
};

/**
 * Where staff act on the incident: the classification, the panel of each transition the user may
 * make (the operator's, #48, #49, #52, and the technician's, #187), and a comment (#50), which is
 * allowed in every status, so its panel follows the others. Each panel receives the incident, to
 * send its `version` back.
 */
export function IncidentActions({ incident }: { incident: Incident }) {
  const role = useSession().user?.role;
  // Most actions change the status, so their form gives way to the next one: the message stays
  // here, until the next action is sent.
  const [done, setDone] = useState<ActionDone | null>(null);
  // The action whose focus was already moved, so a later change of status does not move it again.
  const focusMovedFor = useRef<ActionDone | null>(null);
  const statusPanels = useRef<HTMLDivElement>(null);
  const message = useRef<HTMLParagraphElement>(null);
  const callbacks: ActionCallbacks = { onSending: () => setDone(null), onDone: setDone };
  const classifies = role !== undefined && classifiesIn(incident.status, role);
  const transitions = role === undefined ? [] : availableTransitions(incident.status, actor(role));
  const panelNames = (Object.keys(TRANSITION_PANELS) as TransitionName[]).filter((name) =>
    transitions.some((transition) => transition.name === name),
  );
  const hasPanels = classifies || panelNames.length > 0;

  // The answer and the incident with its new status arrive in either order: act when both did.
  useEffect(() => {
    if (done === null || incident.status === done.from || focusMovedFor.current === done) {
      return;
    }
    focusMovedFor.current = done;
    // The form that had the focus is gone: continue on the next one, or on the message if there is
    // none, instead of leaving the focus on the page.
    // The comment form is not the next step of an action, so the focus does not jump to it.
    const next = statusPanels.current?.querySelector<HTMLElement>(
      'form textarea, form input, form select, form button',
    );
    (next ?? message.current)?.focus();
  }, [done, incident.status]);

  return (
    <section className={`${styles.panel} ${styles.actions}`} aria-labelledby="actions-title">
      <h2 id="actions-title" className={styles.panelTitle}>
        {ACTIONS_TITLE}
      </h2>
      <p ref={message} role="status" tabIndex={-1} className={styles.formMessage}>
        {done?.message}
      </p>
      <div ref={statusPanels} className={styles.statusPanels}>
        {hasPanels ? (
          <>
            {classifies && <ClassificationPanel incident={incident} />}
            {panelNames.map((name) => {
              const Panel = TRANSITION_PANELS[name];
              return <Panel key={name} incident={incident} {...callbacks} />;
            })}
          </>
        ) : (
          <p className={styles.lead}>Only comments are available for this incident.</p>
        )}
      </div>
      <CommentPanel incidentId={incident.id} />
    </section>
  );
}

/**
 * The API only lets a technician open the incidents whose active assignment is theirs, so a
 * technician here is always the assigned one (ADR-0020, "Who").
 */
function actor(role: Role): TransitionActor {
  return { role, isAssignedTechnician: role === 'TECHNICIAN' };
}

/** Operators classify in every status but the final ones, `REJECTED` and `CLOSED` (ADR-0020). */
function classifiesIn(status: Incident['status'], role: Role): boolean {
  return OPERATOR_ROLES.includes(role) && OPEN_STATUSES.includes(status);
}
