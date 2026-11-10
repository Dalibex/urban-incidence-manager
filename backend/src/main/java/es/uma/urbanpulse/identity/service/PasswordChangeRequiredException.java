package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * Right credentials, but the password must change before a session can start ({@code 403}, #165).
 * The client calls {@code createPasswordChange} and signs in again.
 */
public class PasswordChangeRequiredException extends ApiProblemException {

    public PasswordChangeRequiredException() {
        super(
                HttpStatus.FORBIDDEN,
                "PASSWORD_CHANGE_REQUIRED",
                "Change your password before signing in.");
    }
}
