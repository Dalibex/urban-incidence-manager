import type { components } from '@urbanpulse/api-client';
import type { ReactNode } from 'react';

import { ClassificationPanel } from './ClassificationPanel';
import styles from './Incidents.module.css';

type Incident = components['schemas']['IncidentResponse'];

/**
 * Where the operator acts on the incident. Each feature adds the panels of its statuses to the
 * branch of that status below (#48, #49, #50, #52), so the detail page itself does not change.
 * Each panel receives the incident, to send its `version` back.
 */
export function IncidentActions({ incident }: { incident: Incident }) {
  const panels = panelsFor(incident);
  return (
    <section className={`${styles.panel} ${styles.actions}`} aria-labelledby="actions-title">
      <h2 id="actions-title" className={styles.panelTitle}>
        Actions
      </h2>
      {panels ?? <p className={styles.lead}>No actions are available for this incident.</p>}
    </section>
  );
}

function panelsFor(incident: Incident): ReactNode {
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
