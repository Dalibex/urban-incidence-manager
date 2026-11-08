package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.shared.error.RuleViolationException;

/**
 * A citizen can only add photos while their incident is {@code REPORTED}; afterwards the incident
 * is in the hands of the staff. Its code is {@code ATTACHMENT_NOT_ALLOWED} ({@code docs/api.md}).
 */
public class AttachmentNotAllowedException extends RuleViolationException {

    static final String CODE = "ATTACHMENT_NOT_ALLOWED";

    AttachmentNotAllowedException() {
        super(CODE, "Photos can only be added while the incident is REPORTED.");
    }
}
