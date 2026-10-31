package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.Assignment;
import es.uma.urbanpulse.incidents.entity.Incident;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Who can see which incident (docs/security.md, permission matrix), as a query condition, so every
 * read applies the same rule in the database: listings only return what the caller may see, and
 * reading any other incident finds nothing ({@code 404}).
 *
 * <ul>
 *   <li>{@code CITIZEN}: the incidents they reported.
 *   <li>{@code TECHNICIAN}: the incidents whose active assignment is theirs.
 *   <li>{@code MUNICIPAL_OPERATOR}, {@code ADMINISTRATOR}: every incident.
 *   <li>{@code ANALYST}: none; the controllers already answer {@code 403}.
 * </ul>
 */
final class IncidentReadAccess {

    private IncidentReadAccess() {}

    static Specification<Incident> visibleTo(UUID userId, Role role) {
        return switch (role) {
            case CITIZEN -> reportedBy(userId);
            case TECHNICIAN -> activelyAssignedTo(userId);
            case MUNICIPAL_OPERATOR, ADMINISTRATOR -> Specification.unrestricted();
            case ANALYST -> (incident, query, cb) -> cb.disjunction();
        };
    }

    static Specification<Incident> withId(UUID incidentId) {
        return (incident, query, cb) -> cb.equal(incident.get("id"), incidentId);
    }

    private static Specification<Incident> reportedBy(UUID userId) {
        return (incident, query, cb) -> cb.equal(incident.get("reporter").get("id"), userId);
    }

    private static Specification<Incident> activelyAssignedTo(UUID technicianId) {
        return (incident, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var assignment = subquery.from(Assignment.class);
            subquery.select(assignment.get("id"))
                    .where(
                            cb.equal(assignment.get("incident"), incident),
                            cb.isTrue(assignment.get("isActive")),
                            cb.equal(assignment.get("technician").get("id"), technicianId));
            return cb.exists(subquery);
        };
    }
}
