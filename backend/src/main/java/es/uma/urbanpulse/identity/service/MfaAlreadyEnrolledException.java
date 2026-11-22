package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * The account already has a second factor, so its challenge asks for a code, not an enrolment
 * ({@code 409}). An administrator resets it when the phone is lost.
 */
public class MfaAlreadyEnrolledException extends ApiProblemException {

    public MfaAlreadyEnrolledException() {
        super(
                HttpStatus.CONFLICT,
                "MFA_ALREADY_ENROLLED",
                "This account already has a second factor.");
    }
}
