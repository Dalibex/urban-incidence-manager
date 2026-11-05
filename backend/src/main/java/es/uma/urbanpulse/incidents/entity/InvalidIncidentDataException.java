package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.util.List;

/**
 * The change is allowed, but its data is missing or not valid: a reason (ADR-0020, "Required
 * data"), an active department and technician for an assignment, the category and priority of an
 * incident being validated, or the justification of a priority. One {@link FieldError} per field.
 */
public class InvalidIncidentDataException extends InvalidDataException {

    public static InvalidIncidentDataException invalidCategory() {
        return new InvalidIncidentDataException(
                List.of(
                        new FieldError(
                                "categoryId", "The category does not exist or is not active.")));
    }

    /**
     * Ids in the request that point to nothing, such as an unknown department: found by the service
     * that looks them up, before the entity can check the data.
     */
    public static InvalidIncidentDataException unknownReferences(List<FieldError> fieldErrors) {
        return new InvalidIncidentDataException(fieldErrors);
    }

    /** The {@code version} of a status change or a classification is missing or negative. */
    public static InvalidIncidentDataException invalidVersion() {
        return new InvalidIncidentDataException(
                List.of(new FieldError("version", "Send the version of the incident you read.")));
    }

    InvalidIncidentDataException(List<FieldError> fieldErrors) {
        super(fieldErrors);
    }

    /**
     * Every invalid field of a classification: the category the service looks up, and the priority
     * and its justification ({@link Incident#priorityErrors}), reported at once.
     */
    public static InvalidIncidentDataException of(List<FieldError> fieldErrors) {
        return new InvalidIncidentDataException(fieldErrors);
    }
}
