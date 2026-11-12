package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** No user has this id ({@code 404}). */
public class UserNotFoundException extends ApiProblemException {

    public UserNotFoundException(UUID userId) {
        super(HttpStatus.NOT_FOUND, "User " + userId + " does not exist.");
    }
}
