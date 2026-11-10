import { Link, useLocation } from 'react-router';

import { incidentPath, type ListReturn } from '../routing/access';
import { IncidentListPage } from './IncidentListPage';
import styles from './Incidents.module.css';

/**
 * The technician's work queue (#187): the API only lists the incidents actively assigned to them
 * (docs/security.md), so the filters work inside that list and never show anyone else's work.
 */
export function WorkQueuePage() {
  // The detail links back to the queue as the technician left it: same filters, same page.
  const queueReturn: ListReturn = { listSearch: useLocation().search };
  return (
    <IncidentListPage
      title="Work queue"
      lead="The incidents assigned to you, newest first. Open one to accept or resolve it."
      noIncidents={{
        message: 'No incidents are assigned to you.',
        hint: 'An incident appears here as soon as an operator assigns it to you.',
      }}
      rowAction={(incident) => (
        <Link
          className={styles.manage}
          to={incidentPath(incident.id)}
          state={queueReturn}
          aria-label={`Open ${incident.title}`}
        >
          Open
        </Link>
      )}
    />
  );
}
