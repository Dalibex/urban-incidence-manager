package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * Wrong email or wrong password: the same answer for both, so sign-in does not reveal which emails
 * have an account ({@code 401}).
 */
public class InvalidCredentialsException extends ApiProblemException {

    public InvalidCredentialsException() {
        super(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "The email or the password is not correct.");
    }
}
