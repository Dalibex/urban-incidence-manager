package es.uma.urbanpulse.shared.web;

import java.util.Objects;

/**
 * One invalid field of a {@code 422} response, as {@code FieldError} in {@code
 * docs/api/openapi.yml}: the field of the request (or the data the operation needs) and why it is
 * not valid.
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
