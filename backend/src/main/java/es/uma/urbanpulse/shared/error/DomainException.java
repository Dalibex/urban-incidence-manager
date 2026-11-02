package es.uma.urbanpulse.shared.error;

import java.util.List;

/**
 * An expected failure that a domain rule raises, with a stable {@code code} from the catalogue in
 * {@code docs/api.md}. It says what went wrong, not how to answer it: there are only two kinds, and
 * {@code ApiExceptionHandler} maps each one to its status, so the domain never depends on HTTP.
 *
 * <p>The message is shown to the client, so it never carries personal data, SQL or internal ids.
 */
public abstract sealed class DomainException extends RuntimeException
        permits RuleViolationException, InvalidDataException {

    private final String code;

    DomainException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    /** The stable {@code code} of {@code docs/api.md}. */
    public String code() {
        return code;
    }

    /** The invalid fields; empty unless the data is not valid. */
    public List<FieldError> fieldErrors() {
        return List.of();
    }
}
