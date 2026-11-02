package es.uma.urbanpulse.shared.error;

import java.util.List;

/**
 * The operation is allowed, but data it needs is missing or not valid, one {@link FieldError} per
 * field. Answered with {@code 422} and the code {@code VALIDATION_FAILED}, in the same shape as the
 * errors of bean validation.
 */
public abstract non-sealed class InvalidDataException extends DomainException {

    public static final String CODE = "VALIDATION_FAILED";

    private final List<FieldError> fieldErrors;

    protected InvalidDataException(List<FieldError> fieldErrors) {
        super(CODE, summary(fieldErrors.size()));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    /** The detail of every {@code VALIDATION_FAILED}: how many fields are not valid. */
    public static String summary(int invalidFields) {
        return invalidFields == 1
                ? "The request has 1 invalid field."
                : "The request has " + invalidFields + " invalid fields.";
    }

    @Override
    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
