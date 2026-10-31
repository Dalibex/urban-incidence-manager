package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.StatusChange;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code StatusChangeResponse}: one entry of the status history (ADR-0020). {@code actor} and
 * {@code visibleToCitizen} are staff-only, so a citizen gets a type without them; the citizen's
 * {@code reason} is {@code null} where ADR-0020 hides it (docs/api.md, "Absent and null").
 */
public sealed interface StatusChangeResponse {

    static StatusChangeResponse of(StatusChange change, Role role) {
        return role == Role.CITIZEN ? Citizen.of(change) : Staff.of(change);
    }

    /** {@code StatusChangeActor}: who made the change. */
    record Actor(UUID id, String name, Role role) {

        static Actor of(User user) {
            return new Actor(user.getId(), user.getName(), user.getRole());
        }
    }

    record Staff(
            UUID id,
            Instant instant,
            IncidentStatus previousStatus,
            IncidentStatus newStatus,
            String reason,
            Actor actor,
            boolean visibleToCitizen)
            implements StatusChangeResponse {

        static Staff of(StatusChange change) {
            return new Staff(
                    change.getId(),
                    change.getInstant().toInstant(),
                    change.getPreviousStatus(),
                    change.getNewStatus(),
                    change.getReason(),
                    Actor.of(change.getActor()),
                    change.isVisibleToCitizen());
        }
    }

    record Citizen(
            UUID id,
            Instant instant,
            IncidentStatus previousStatus,
            IncidentStatus newStatus,
            String reason)
            implements StatusChangeResponse {

        static Citizen of(StatusChange change) {
            return new Citizen(
                    change.getId(),
                    change.getInstant().toInstant(),
                    change.getPreviousStatus(),
                    change.getNewStatus(),
                    change.citizenSeesReason() ? change.getReason() : null);
        }
    }
}
