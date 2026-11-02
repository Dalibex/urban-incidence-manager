package es.uma.urbanpulse.shared.error;

import java.util.Objects;

/**
 * One invalid field: the field of the request (or the data the operation needs) and why it is not
 * valid. Domain errors and bean validation both describe their fields with it, and the API writes
 * it as {@code FieldError} of {@code docs/api/openapi.yml}, in the {@code errors} of a {@code 422}.
 *
 * @param field path of the property in the request body, or name of the missing data
 * @param message why the value is not valid, shown to the user
 */
public record FieldError(String field, String message) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
