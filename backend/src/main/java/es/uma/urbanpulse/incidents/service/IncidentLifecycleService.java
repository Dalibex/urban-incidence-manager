package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.entity.InvalidTransitionException;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes the status of an incident (ADR-0020). Every lifecycle action goes through here, so all of
 * them apply the same access rule, the same conflict control and the same transaction.
 *
 * <p>The status, the history entry and the assignment are written in one transaction: if anything
 * fails, nothing is saved. Conflicts are detected with the incident's {@code version}: the caller
 * sends the version it read, and a change made since then answers {@code 409} with the code {@code
 * CONCURRENT_MODIFICATION} instead of overwriting it.
 */
@Service
@Transactional
public class IncidentLifecycleService {

    private final IncidentRepository incidents;
    private final UserRepository users;
    private final Clock clock;

    public IncidentLifecycleService(
            IncidentRepository incidents, UserRepository users, Clock clock) {
        this.incidents = incidents;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Applies the transition to the incident, as the given user.
     *
     * @param expectedVersion the {@code version} of the incident the caller read
     * @return the incident after the change, with its new {@code version}
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     * @throws ObjectOptimisticLockingFailureException if the incident changed since the caller read
     *     it, now or while this change was being saved
     * @throws InvalidTransitionException if the table does not allow it from the current status or
     *     for this user
     * @throws InvalidIncidentDataException if data the transition needs is missing or not valid
     */
    public IncidentResponse changeStatus(
            UUID incidentId,
            int expectedVersion,
            IncidentTransition transition,
            TransitionDetails details,
            UUID userId,
            Role role) {
        var incident =
                incidents
                        .findOne(
                                IncidentReadAccess.visibleTo(userId, role)
                                        .and(IncidentReadAccess.withId(incidentId)))
                        .orElseThrow(() -> new IncidentNotFoundException(incidentId));
        // Checked before the transition: a stale caller must reload, even if the change would
        // still be allowed in the new status.
        if (incident.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Incident.class, incidentId);
        }
        var actor =
                users.findById(userId)
                        .orElseThrow(
                                () -> new IllegalStateException("User " + userId + " not found"));

        incident.changeStatus(transition, actor, details, clock.instant());
        // Writes now, so a change committed by someone else after the read above fails here, and
        // the response carries the new version.
        incidents.flush();

        return IncidentResponse.of(incident, role);
    }
}
