package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CommentResponse;
import es.uma.urbanpulse.incidents.entity.Comment;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.repository.CommentRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.shared.web.PageResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal comments of an incident (RF13). Who may read or write them is who may see the
 * incident ({@link IncidentReadAccess}): an operator or an administrator any incident, a technician
 * only the ones actively assigned to them. Any other incident is a {@code 404}. Citizens and
 * analysts never get here: the controller answers {@code 403}.
 */
@Service
public class IncidentCommentService {

    private final IncidentRepository incidents;
    private final CommentRepository comments;
    private final UserRepository users;
    private final Clock clock;

    public IncidentCommentService(
            IncidentRepository incidents,
            CommentRepository comments,
            UserRepository users,
            Clock clock) {
        this.incidents = incidents;
        this.comments = comments;
        this.users = users;
        this.clock = clock;
    }

    /**
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     */
    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(
            UUID incidentId, UUID userId, Role role, Pageable page) {
        visibleIncident(incidentId, userId, role);
        return PageResponse.of(comments.findByIncidentId(incidentId, page), CommentResponse::of);
    }

    /**
     * Adds an internal comment by the caller.
     *
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     * @throws InvalidIncidentDataException if the text is missing, blank or too long
     */
    @Transactional
    public CommentResponse create(UUID incidentId, UUID userId, Role role, String text) {
        var incident = visibleIncident(incidentId, userId, role);
        var author =
                users.findById(userId)
                        .orElseThrow(() -> new InsufficientAuthenticationException("Unknown user"));
        var comment = Comment.internal(incident, author, text, OffsetDateTime.now(clock));
        return CommentResponse.of(comments.save(comment));
    }

    private Incident visibleIncident(UUID incidentId, UUID userId, Role role) {
        return incidents
                .findOne(
                        IncidentReadAccess.visibleTo(userId, role)
                                .and(IncidentReadAccess.withId(incidentId)))
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }
}
