package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The photo does not exist or the caller may not see its incident ({@code 404}): both get the same
 * answer, so the API does not reveal which ids exist (docs/security.md).
 */
public class AttachmentNotFoundException extends ApiProblemException {

    public AttachmentNotFoundException(UUID attachmentId) {
        super(HttpStatus.NOT_FOUND, "Attachment " + attachmentId + " does not exist.");
    }
}
