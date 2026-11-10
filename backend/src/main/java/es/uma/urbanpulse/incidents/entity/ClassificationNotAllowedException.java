package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.shared.error.RuleViolationException;

/**
 * The incident is {@code REJECTED} or {@code CLOSED}, so its category and priority can no longer
 * change (ADR-0020, "Classification is not a transition"). Its code is {@code
 * CLASSIFICATION_NOT_ALLOWED} ({@code docs/api.md}).
 */
public class ClassificationNotAllowedException extends RuleViolationException {

    static final String CODE = "CLASSIFICATION_NOT_ALLOWED";

    ClassificationNotAllowedException(IncidentStatus status) {
        super(CODE, "An incident in status " + status + " cannot be classified.");
    }
}
