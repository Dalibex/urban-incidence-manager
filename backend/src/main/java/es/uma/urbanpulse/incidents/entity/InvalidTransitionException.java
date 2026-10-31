package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * The transition table of ADR-0020 does not allow this change: the incident is not in a status the
 * action starts from, or the user is not the one who may make it. Answered with {@code 409} and the
 * code {@code INVALID_TRANSITION} ({@code docs/api.md}).
 */
public class InvalidTransitionException extends ApiProblemException {

    static final String CODE = "INVALID_TRANSITION";

    private InvalidTransitionException(String detail) {
        super(HttpStatus.CONFLICT, CODE, detail);
    }

    static InvalidTransitionException fromStatus(IncidentStatus current, IncidentStatus target) {
        return new InvalidTransitionException(
                "An incident in status " + current + " cannot change to " + target + ".");
    }

    static InvalidTransitionException notAllowedFor(IncidentTransition transition) {
        return new InvalidTransitionException(
                "You are not allowed to make the " + transition + " of this incident.");
    }

    static InvalidTransitionException reportOnlyByCitizens() {
        return new InvalidTransitionException("Only citizens report incidents.");
    }
}
