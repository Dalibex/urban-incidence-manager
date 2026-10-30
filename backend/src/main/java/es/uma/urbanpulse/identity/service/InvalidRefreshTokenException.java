package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * Unknown, expired, revoked or already used refresh token, or a disabled account: the client signs
 * the user out ({@code 401}).
 */
public class InvalidRefreshTokenException extends ApiProblemException {

    public InvalidRefreshTokenException() {
        super(
                HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN",
                "The session has expired. Sign in again.");
    }
}
