package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The incident does not exist or the caller may not see it ({@code 404}): both get the same answer,
 * so the API does not reveal which ids exist (docs/security.md).
 */
public class IncidentNotFoundException extends ApiProblemException {

    public IncidentNotFoundException(UUID incidentId) {
        super(HttpStatus.NOT_FOUND, "Incident " + incidentId + " does not exist.");
    }
}
