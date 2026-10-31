package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import es.uma.urbanpulse.shared.web.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * The transition is allowed, but the data it needs is missing (ADR-0020, "Required data"): a
 * reason, the department of an assignment, or the category and priority of an incident being
 * validated. Answered with {@code 422} and the code {@code VALIDATION_FAILED}, with one entry in
 * {@code errors} per missing item.
 */
public class MissingTransitionDataException extends ApiProblemException {

    static final String CODE = "VALIDATION_FAILED";

    private final List<FieldError> fieldErrors;

    MissingTransitionDataException(List<FieldError> fieldErrors) {
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
