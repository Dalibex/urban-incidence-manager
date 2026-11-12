import type { components } from '@urbanpulse/api-client';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import { useSession } from '../session/SessionContext';
import { AssignPanel } from './AssignPanel';
import { ClassificationPanel } from './ClassificationPanel';
import { CommentPanel } from './CommentPanel';
import styles from './Incidents.module.css';
import { ClosePanel, ReopenPanel } from './OperatorActions';
import type { ActionCallbacks, ActionDone } from './ActionForm';
import { AcceptPanel, ResolvePanel } from './TechnicianActions';

type Incident = components['schemas']['IncidentResponse'];

/** Shared with the skeleton of the detail, so the panel keeps its title while the incident loads. */
export const ACTIONS_TITLE = 'Actions';

/**
 * Where staff act on the incident. Each feature adds the panels of its statuses to the branch of
 * that status below: the operator's (#48, #49, #52) and the technician's (#187). The detail page
 * itself does not change. Each panel receives the incident, to send its `version` back. A comment
 * (#50) is allowed in every status, so its panel follows the others.
 */
export function IncidentActions({ incident }: { incident: Incident }) {
  const isTechnician = useSession().user?.role === 'TECHNICIAN';
  // An action changes the status, so its form gives way to the next one: the message stays here,
  // until the next action is sent.
  const [done, setDone] = useState<ActionDone | null>(null);
  // The action whose focus was already moved, so a later change of status does not move it again.
  const focusMovedFor = useRef<ActionDone | null>(null);
  const statusPanels = useRef<HTMLDivElement>(null);
  const message = useRef<HTMLParagraphElement>(null);
  const callbacks: ActionCallbacks = { onSending: () => setDone(null), onDone: setDone };
  const panels = isTechnician
    ? technicianPanels(incident, callbacks)
    : operatorPanels(incident, callbacks);

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
        {panels ?? <p className={styles.lead}>Only comments are available for this incident.</p>}
      </div>
      <CommentPanel incidentId={incident.id} />
    </section>
  );
}

function operatorPanels(incident: Incident, callbacks: ActionCallbacks): ReactNode {
  // Classification is allowed in every status but REJECTED and CLOSED (ADR-0020).
  const classification = <ClassificationPanel incident={incident} />;
  switch (incident.status) {
    case 'REPORTED':
      return classification;
    case 'VALIDATED':
    case 'ASSIGNED':
    case 'REOPENED':
      // Assigned to a department and, optionally, a technician; an assigned one is reassigned (#185).
      return (
        <>
          {classification}
          <AssignPanel incident={incident} {...callbacks} />
        </>
      );
    case 'IN_PROGRESS':
      return classification;
    case 'RESOLVED':
      // The operator checks the technician's work: close it, or reopen it with a reason (#52).
      return (
        <>
          {classification}
          <ClosePanel incident={incident} {...callbacks} />
          <ReopenPanel incident={incident} {...callbacks} />
        </>
      );
    case 'REJECTED':
      return null;
    case 'CLOSED':
      return null;
  }
}

/**
 * The technician of the active assignment accepts and then resolves (ADR-0020). The API only lets
 * them open their own incidents, so every incident here is one they may act on.
 */
function technicianPanels(incident: Incident, callbacks: ActionCallbacks): ReactNode {
  switch (incident.status) {
    case 'ASSIGNED':
      return <AcceptPanel incident={incident} {...callbacks} />;
    case 'IN_PROGRESS':
      return <ResolvePanel incident={incident} {...callbacks} />;
    default:
      return null;
  }
}
