import { ActionForm, type PanelProps } from './ActionForm';
import { useCloseIncident, useReopenIncident } from './incident-queries';

/** Operators see every incident, so a 404 means it is no longer there (docs/security.md). */
const NOT_FOUND = 'This incident is no longer available. Reload it to check.';

/** `RESOLVED` to `CLOSED`, which is final, once the operator checked the work (#52, ADR-0020). */
export function ClosePanel({ incident, ...callbacks }: PanelProps) {
  const close = useCloseIncident(incident.id);
  return (
    <ActionForm
      incident={incident}
      title="Verify and close"
      lead="Close it once you have checked the work. Closing is final, and the citizen sees it as closed."
      submitLabel="Close the incident"
      done="Incident closed."
      notFound={NOT_FOUND}
      sending={close.isPending}
      send={(version) => close.mutateAsync({ version })}
      {...callbacks}
    />
  );
}

/**
 * `RESOLVED` to `REOPENED`, with the reason the API requires, when the work is not well done. The
 * incident can then be assigned again (#52, ADR-0020).
 */
export function ReopenPanel({ incident, ...callbacks }: PanelProps) {
  const reopen = useReopenIncident(incident.id);
  return (
    <ActionForm
      incident={incident}
      title="Reopen"
      lead="Reopen it when the work is not well done, so it can be assigned again. The citizen sees it as reopened, not your reason."
      submitLabel="Reopen the incident"
      done="Incident reopened: it can be assigned again."
      notFound={NOT_FOUND}
      notes={{ label: 'Reason for reopening', missing: 'Write why the work is not well done.' }}
      sending={reopen.isPending}
      send={(version, reason) => reopen.mutateAsync({ version, reason })}
      {...callbacks}
    />
  );
}
