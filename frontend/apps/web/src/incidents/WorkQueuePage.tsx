import { IncidentListPage } from './IncidentListPage';

/**
 * The technician's work queue (#187): the API only lists the incidents actively assigned to them
 * (docs/security.md), so the filters work inside that list and never show anyone else's work.
 */
export function WorkQueuePage() {
  return (
    <IncidentListPage
      title="Work queue"
      lead="The incidents assigned to you, newest first."
      noIncidents={{
        message: 'No incidents are assigned to you.',
        hint: 'An incident appears here as soon as an operator assigns it to you.',
      }}
    />
  );
}
