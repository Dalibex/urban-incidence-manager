import { ActionForm, type PanelProps } from './ActionForm';
import { useAcceptIncident, useResolveIncident } from './incident-queries';

/** A technician gets 404 once the incident is no longer assigned to them (docs/security.md). */
const NOT_ASSIGNED = 'This incident is no longer assigned to you. Reload it to check.';

/** `ASSIGNED` to `IN_PROGRESS`: the technician starts the work (#187, ADR-0020). */
export function AcceptPanel({ incident, ...callbacks }: PanelProps) {
  const accept = useAcceptIncident(incident.id);
  return (
    <ActionForm
      incident={incident}
      title="Accept the work"
      lead="Accept it when you start working on it. The citizen sees it as in progress."
      submitLabel="Accept"
      done="Incident accepted: it is now in progress."
      notFound={NOT_ASSIGNED}
      sending={accept.isPending}
      send={(version) => accept.mutateAsync({ version })}
      {...callbacks}
    />
  );
}

/** `IN_PROGRESS` to `RESOLVED`, with the resolution notes the API requires (#187, ADR-0020). */
export function ResolvePanel({ incident, ...callbacks }: PanelProps) {
  const resolve = useResolveIncident(incident.id);
  return (
    <ActionForm
      incident={incident}
      title="Resolve"
      lead="Resolve it when the work is done. The citizen sees it as resolved, not your notes."
      submitLabel="Mark as resolved"
      done="Incident resolved."
      notFound={NOT_ASSIGNED}
      notes={{ label: 'Resolution notes', missing: 'Write what was done.' }}
      sending={resolve.isPending}
      send={(version, notes) => resolve.mutateAsync({ version, reason: notes })}
      {...callbacks}
    />
  );
}
