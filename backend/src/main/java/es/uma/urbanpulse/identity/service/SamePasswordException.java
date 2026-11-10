package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.util.List;

/** The new password is the current one, so the change would change nothing ({@code 422}). */
public class SamePasswordException extends InvalidDataException {

    SamePasswordException() {
        super(
                List.of(
                        new FieldError(
                                "newPassword", "must be different from the current password")));
    }
}
