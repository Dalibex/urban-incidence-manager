package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * Unknown, expired or spent sign-in challenge, or an account whose role no longer needs the second
 * factor: the client goes back to the password step ({@code 401}).
 */
public class InvalidMfaChallengeException extends ApiProblemException {

    public InvalidMfaChallengeException() {
        super(
                HttpStatus.UNAUTHORIZED,
                "INVALID_MFA_CHALLENGE",
                "The sign-in has expired. Sign in again.");
    }
}
