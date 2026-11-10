import { ActionForm, type PanelProps } from './ActionForm';
import { useValidateIncident } from './incident-queries';

/** Operators see every incident, so a 404 means it is no longer there (docs/security.md). */
const NOT_FOUND = 'This incident is no longer available. Reload it to check.';

/**
 * `REPORTED` to `VALIDATED` (#189, ADR-0020). The API needs the category and the priority first,
 * which the operator sets with the classification above, so the button waits for them.
 */
export function ValidatePanel({ incident, ...callbacks }: PanelProps) {
  const validate = useValidateIncident(incident.id);
  const classified = incident.category != null && incident.priority != null;
  return (
    <ActionForm
      incident={incident}
      title="Validate"
      lead="Validate it when it is a real incident the city council handles, so it can be assigned. The citizen sees it as validated."
      submitLabel="Validate the incident"
      done="Incident validated: it can be assigned now."
      notFound={NOT_FOUND}
      blocked={
        classified
          ? undefined
          : 'Set its category and priority in Classification and save them first.'
      }
      sending={validate.isPending}
      send={(version) => validate.mutateAsync({ version })}
      {...callbacks}
    />
  );
}
