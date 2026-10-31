package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import es.uma.urbanpulse.shared.web.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * The change is allowed, but its data is missing or not valid: a reason (ADR-0020, "Required
 * data"), an active department and technician for an assignment, the category and priority of an
 * incident being validated, or the justification of a priority. Answered with {@code 422} and the
 * code {@code VALIDATION_FAILED}, with one entry in {@code errors} per field.
 */
public class InvalidIncidentDataException extends ApiProblemException {

    static final String CODE = "VALIDATION_FAILED";

    private final List<FieldError> fieldErrors;

    InvalidIncidentDataException(List<FieldError> fieldErrors) {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                CODE,
                fieldErrors.size() == 1
                        ? "The request has 1 invalid field."
                        : "The request has " + fieldErrors.size() + " invalid fields.");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    @Override
    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
