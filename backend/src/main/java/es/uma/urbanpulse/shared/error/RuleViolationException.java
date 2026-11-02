package es.uma.urbanpulse.shared.error;

/**
 * A rule does not allow the operation in the current state of the data, such as a status change
 * that the transition table of ADR-0020 does not have. Answered with {@code 409}.
 */
public abstract non-sealed class RuleViolationException extends DomainException {

    protected RuleViolationException(String code, String detail) {
        super(code, detail);
    }
}
