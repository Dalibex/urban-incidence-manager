package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.util.List;

/**
 * A user body that is not valid, or a change that breaks a rule of user management ({@code 422}): a
 * citizen role on a staff account, or administrators changing their own role or disabling
 * themselves. Every invalid field goes in one answer.
 */
public class InvalidUserChangeException extends InvalidDataException {

    InvalidUserChangeException(List<FieldError> fieldErrors) {
        super(fieldErrors);
    }
}
