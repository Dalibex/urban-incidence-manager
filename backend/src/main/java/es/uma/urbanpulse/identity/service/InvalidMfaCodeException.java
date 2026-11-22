package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * The code is wrong, outside its time window or already used: the user types another one ({@code
 * 401}).
 */
public class InvalidMfaCodeException extends ApiProblemException {

    public InvalidMfaCodeException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "The code is not correct.");
    }
}
