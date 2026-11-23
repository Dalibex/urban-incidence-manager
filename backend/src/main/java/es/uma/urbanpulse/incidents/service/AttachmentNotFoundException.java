package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The photo does not exist, the caller may not see its incident, or the caller is a citizen who did
 * not upload it (#284) ({@code 404}): all get the same answer, so the API does not reveal which ids
 * exist (docs/security.md).
 */
public class AttachmentNotFoundException extends ApiProblemException {

    public AttachmentNotFoundException(UUID attachmentId) {
        super(HttpStatus.NOT_FOUND, "Attachment " + attachmentId + " does not exist.");
    }
}
