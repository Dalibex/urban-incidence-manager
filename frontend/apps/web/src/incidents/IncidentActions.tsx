import type { components } from '@urbanpulse/api-client';
import { useState, type ReactNode } from 'react';

import { useSession } from '../session/SessionContext';
import { ClassificationPanel } from './ClassificationPanel';
import styles from './Incidents.module.css';
import { AcceptPanel, ResolvePanel } from './TechnicianActions';

type Incident = components['schemas']['IncidentResponse'];

/**
 * Where staff act on the incident. Each feature adds the panels of its statuses to the branch of
 * that status below: the operator's (#48, #49, #50, #52) and the technician's (#187). The detail
 * page itself does not change. Each panel receives the incident, to send its `version` back.
 */
export function IncidentActions({ incident }: { incident: Incident }) {
  const isTechnician = useSession().user?.role === 'TECHNICIAN';
  // An action changes the status, so its form gives way to the next one: the message stays here.
  const [done, setDone] = useState<string | null>(null);
  const panels = isTechnician ? technicianPanels(incident, setDone) : operatorPanels(incident);
  return (
    <section className={`${styles.panel} ${styles.actions}`} aria-labelledby="actions-title">
      <h2 id="actions-title" className={styles.panelTitle}>
        Actions
      </h2>
      <p role="status" className={styles.formMessage}>
        {done}
      </p>
      {panels ?? <p className={styles.lead}>No actions are available for this incident.</p>}
    </section>
  );
}

function operatorPanels(incident: Incident): ReactNode {
  // Classification is allowed in every status but REJECTED and CLOSED (ADR-0020).
  const classification = <ClassificationPanel incident={incident} />;
  switch (incident.status) {
    case 'REPORTED':
      return classification;
    case 'VALIDATED':
      return classification;
    case 'ASSIGNED':
      return classification;
    case 'IN_PROGRESS':
      return classification;
    case 'RESOLVED':
      return classification;
    case 'REOPENED':
      return classification;
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
function technicianPanels(incident: Incident, onDone: (message: string) => void): ReactNode {
  switch (incident.status) {
    case 'ASSIGNED':
      return <AcceptPanel incident={incident} onDone={onDone} />;
    case 'IN_PROGRESS':
      return <ResolvePanel incident={incident} onDone={onDone} />;
    default:
      return null;
  }
}
