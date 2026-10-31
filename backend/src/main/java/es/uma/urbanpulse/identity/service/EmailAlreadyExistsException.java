package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/** Sign-up with an email that already has an account ({@code 409}). */
public class EmailAlreadyExistsException extends ApiProblemException {

    public EmailAlreadyExistsException() {
        super(
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_EXISTS",
                "An account with this email already exists.");
    }
}
