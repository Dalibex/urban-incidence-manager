package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.incidents.entity.Assignment;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filters of {@code listIncidents} ({@code docs/api/openapi.yml}). An empty set or a {@code
 * null} instant means that filter is not used. Every filter only narrows the list: the service
 * combines it with {@link IncidentReadAccess}, so it never widens what the caller may see.
 *
 * @param statuses only incidents in one of these statuses
 * @param categoryIds only incidents in one of these categories
 * @param priorities only incidents with one of these priorities
 * @param reportedFrom only incidents reported at or after this instant; {@code null} for no limit
 * @param reportedTo only incidents reported before this instant; {@code null} for no limit
 * @param departmentIds only incidents whose active assignment is in one of these departments
 */
public record IncidentFilter(
        Set<IncidentStatus> statuses,
        Set<UUID> categoryIds,
        Set<Priority> priorities,
        Instant reportedFrom,
        Instant reportedTo,
        Set<UUID> departmentIds) {

    /**
     * @throws MalformedRequestException if {@code reportedFrom} is not earlier than {@code
     *     reportedTo}
     */
    public IncidentFilter {
        statuses = Set.copyOf(statuses);
        categoryIds = Set.copyOf(categoryIds);
        priorities = Set.copyOf(priorities);
        departmentIds = Set.copyOf(departmentIds);
        if (reportedFrom != null && reportedTo != null && !reportedFrom.isBefore(reportedTo)) {
            throw new MalformedRequestException("reportedFrom must be earlier than reportedTo.");
        }
    }

    /**
     * Whether it uses a filter only staff may use. {@code status} is open to every role that can
     * list, because the citizen's own reports use it; the others are not (docs/security.md).
     */
    public boolean usesStaffFilters() {
        return !categoryIds.isEmpty()
                || !priorities.isEmpty()
                || reportedFrom != null
                || reportedTo != null
                || !departmentIds.isEmpty();
    }

    /** The filters as one query condition; unrestricted when none is used. */
    Specification<Incident> specification() {
        var conditions = new ArrayList<Specification<Incident>>();
        if (!statuses.isEmpty()) {
            conditions.add((incident, query, cb) -> incident.get("status").in(statuses));
        }
        if (!categoryIds.isEmpty()) {
            conditions.add(
                    (incident, query, cb) -> incident.get("category").get("id").in(categoryIds));
        }
        if (!priorities.isEmpty()) {
            conditions.add((incident, query, cb) -> incident.get("priority").in(priorities));
        }
        if (reportedFrom != null) {
            conditions.add(
                    (incident, query, cb) ->
                            cb.greaterThanOrEqualTo(
                                    incident.get("reportedAt"),
                                    reportedFrom.atOffset(ZoneOffset.UTC)));
        }
        if (reportedTo != null) {
            conditions.add(
                    (incident, query, cb) ->
                            cb.lessThan(
                                    incident.get("reportedAt"),
                                    reportedTo.atOffset(ZoneOffset.UTC)));
        }
        if (!departmentIds.isEmpty()) {
            conditions.add(activelyAssignedToOneOf(departmentIds));
        }
        return Specification.allOf(conditions);
    }

    // Only the active assignment counts: an incident reassigned to another department no longer
    // belongs to the first one.
    private static Specification<Incident> activelyAssignedToOneOf(Set<UUID> departmentIds) {
        return (incident, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var assignment = subquery.from(Assignment.class);
            subquery.select(assignment.get("id"))
                    .where(
                            cb.equal(assignment.get("incident"), incident),
                            cb.isTrue(assignment.get("isActive")),
                            assignment.get("department").get("id").in(departmentIds));
            return cb.exists(subquery);
        };
    }
}
