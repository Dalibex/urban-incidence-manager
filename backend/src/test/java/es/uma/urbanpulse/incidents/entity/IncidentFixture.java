package es.uma.urbanpulse.incidents.entity;

import static es.uma.urbanpulse.incidents.entity.IncidentTransition.ACCEPTANCE;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.ASSIGNMENT;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.CLOSURE;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.REJECTION;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.REOPENING;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.RESOLUTION;
import static es.uma.urbanpulse.incidents.entity.IncidentTransition.VALIDATION;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import java.time.Instant;
import java.util.UUID;

/**
 * Users and incidents for the lifecycle tests, built in memory through the public API of {@link
 * Incident}, so an incident in any status got there through allowed transitions.
 */
final class IncidentFixture {

    static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    static final Instant LATER = Instant.parse("2026-11-02T10:30:00Z");

    final User citizen = user(Role.CITIZEN);
    final User operator = user(Role.MUNICIPAL_OPERATOR);
    final User administrator = user(Role.ADMINISTRATOR);
    final User technician = user(Role.TECHNICIAN);
    final User otherTechnician = user(Role.TECHNICIAN);
    final User analyst = user(Role.ANALYST);
    final Department department = department();

    /** A reported incident with the category and priority that validation needs. */
    Incident reported() {
        var incident =
                Incident.report(
                        citizen, "Street light off", "Off since Monday.", null, REPORTED_AT);
        incident.classify(category(), REPORTED_AT);
        incident.prioritize(
                Priority.MEDIUM, "Dark street", PrioritySource.MANUAL, operator, REPORTED_AT);
        return incident;
    }

    /** An incident in this status, reached through the transitions of ADR-0020. */
    Incident in(IncidentStatus status) {
        var incident = reported();
        switch (status) {
            case REPORTED -> {}
            case VALIDATED -> apply(incident, VALIDATION);
            case REJECTED -> apply(incident, REJECTION);
            case ASSIGNED -> apply(incident, VALIDATION, ASSIGNMENT);
            case IN_PROGRESS -> apply(incident, VALIDATION, ASSIGNMENT, ACCEPTANCE);
            case RESOLVED -> apply(incident, VALIDATION, ASSIGNMENT, ACCEPTANCE, RESOLUTION);
            case CLOSED -> apply(incident, VALIDATION, ASSIGNMENT, ACCEPTANCE, RESOLUTION, CLOSURE);
            case REOPENED ->
                    apply(incident, VALIDATION, ASSIGNMENT, ACCEPTANCE, RESOLUTION, REOPENING);
        }
        return incident;
    }

    /** The data each transition needs: a reason, and the department and technician to assign. */
    TransitionDetails completeDetails(IncidentTransition transition) {
        return transition == ASSIGNMENT
                ? TransitionDetails.assignment(department, technician)
                : TransitionDetails.withReason("Reason for " + transition);
    }

    /** The user who may make the transition: an operator, or the assigned technician. */
    User performerOf(IncidentTransition transition) {
        return transition.performer() == IncidentTransition.Performer.OPERATOR
                ? operator
                : technician;
    }

    private void apply(Incident incident, IncidentTransition... transitions) {
        for (var transition : transitions) {
            incident.changeStatus(
                    transition, performerOf(transition), completeDetails(transition), REPORTED_AT);
        }
    }

    static User user(Role role) {
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setName(role + " user");
        user.setEmail(role.name().toLowerCase() + "-" + user.getId() + "@example.com");
        user.setRole(role);
        return user;
    }

    static Category category() {
        var category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Lighting");
        return category;
    }

    static Department department() {
        var department = new Department();
        department.setId(UUID.randomUUID());
        department.setName("Public lighting");
        return department;
    }
}
