import { Link, useLocation } from 'react-router';

import { incidentPath, type ListReturn } from '../routing/access';
import { IncidentListPage } from './IncidentListPage';
import styles from './Incidents.module.css';

/**
 * The operator's inbox (#46, #183): every incident, since the API lets operators and administrators
 * see them all. The menu opens it on the reports waiting for a review.
 */
export function InboxPage() {
  // The detail links back to the inbox as the user left it: same filters, same page.
  const inboxReturn: ListReturn = { listSearch: useLocation().search };
  return (
    <IncidentListPage
      title="Inbox"
      lead="Newest first. Filter the list, then open an incident to review and manage it."
      noIncidents={{
        message: 'There are no incidents yet.',
        hint: 'New reports from citizens appear here as soon as they are sent.',
      }}
      rowAction={(incident) => (
        <Link
          className={styles.manage}
          to={incidentPath(incident.id)}
          state={inboxReturn}
          aria-label={`Manage ${incident.title}`}
        >
          Manage
        </Link>
      )}
    />
  );
}
