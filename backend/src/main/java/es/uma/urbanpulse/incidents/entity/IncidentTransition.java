package es.uma.urbanpulse.incidents.entity;

import static es.uma.urbanpulse.incidents.entity.IncidentStatus.ASSIGNED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.CLOSED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.IN_PROGRESS;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.REJECTED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.REOPENED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.REPORTED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.RESOLVED;
import static es.uma.urbanpulse.incidents.entity.IncidentStatus.VALIDATED;

import es.uma.urbanpulse.identity.entity.Role;
import java.util.EnumSet;
import java.util.Set;

/**
 * The transition table of ADR-0020: every status change an incident allows, who may make it and
 * what its reason is for. Each constant is one domain action of the API (a sub-resource such as
 * {@code /validation}); a change that no constant describes is not allowed.
 *
 * <p>Only {@link Incident#changeStatus} applies it. {@code canTransition} in {@code
 * frontend/packages/domain} repeats this table to show or hide buttons; the backend always checks.
 */
public enum IncidentTransition {
    INFORMATION_REQUEST(
            EnumSet.of(REPORTED), REPORTED, Performer.OPERATOR, Reason.SHOWN_TO_CITIZEN),
    VALIDATION(EnumSet.of(REPORTED), VALIDATED, Performer.OPERATOR, Reason.OPTIONAL),
    REJECTION(EnumSet.of(REPORTED), REJECTED, Performer.OPERATOR, Reason.SHOWN_TO_CITIZEN),
    // Also the reassignment (ASSIGNED -> ASSIGNED), which deactivates the previous assignment.
    ASSIGNMENT(
            EnumSet.of(VALIDATED, REOPENED, ASSIGNED),
            ASSIGNED,
            Performer.OPERATOR,
            Reason.OPTIONAL),
    ACCEPTANCE(EnumSet.of(ASSIGNED), IN_PROGRESS, Performer.ASSIGNED_TECHNICIAN, Reason.OPTIONAL),
    // The reason holds the resolution notes.
    RESOLUTION(EnumSet.of(IN_PROGRESS), RESOLVED, Performer.ASSIGNED_TECHNICIAN, Reason.INTERNAL),
    CLOSURE(EnumSet.of(RESOLVED), CLOSED, Performer.OPERATOR, Reason.OPTIONAL),
    REOPENING(EnumSet.of(RESOLVED), REOPENED, Performer.OPERATOR, Reason.INTERNAL);

    /** Who may make a transition. */
    public enum Performer {
        /**
         * A {@code MUNICIPAL_OPERATOR} or an {@code ADMINISTRATOR}, who can do what an operator
         * does.
         */
        OPERATOR,
        /** The {@code TECHNICIAN} of the active assignment of the incident. */
        ASSIGNED_TECHNICIAN;

        boolean hasRole(Role role) {
            return switch (this) {
                case OPERATOR -> role == Role.MUNICIPAL_OPERATOR || role == Role.ADMINISTRATOR;
                case ASSIGNED_TECHNICIAN -> role == Role.TECHNICIAN;
            };
        }
    }

    /** Whether a transition needs a reason and whether the citizen sees it ("Citizen sees"). */
    public enum Reason {
        /** Not needed; if given, only staff see it. */
        OPTIONAL,
        /** Required; only staff see it. */
        INTERNAL,
        /** Required; the citizen sees it in the history. */
        SHOWN_TO_CITIZEN
    }

    private final Set<IncidentStatus> origins;
    private final IncidentStatus target;
    private final Performer performer;
    private final Reason reason;

    IncidentTransition(
            Set<IncidentStatus> origins,
            IncidentStatus target,
            Performer performer,
            Reason reason) {
        this.origins = Set.copyOf(origins);
        this.target = target;
        this.performer = performer;
        this.reason = reason;
    }

    public Set<IncidentStatus> origins() {
        return origins;
    }

    public IncidentStatus target() {
        return target;
    }

    public Performer performer() {
        return performer;
    }

    public boolean startsFrom(IncidentStatus status) {
        return origins.contains(status);
    }

    public boolean requiresReason() {
        return reason != Reason.OPTIONAL;
    }

    /**
     * Whether a citizen sees the reason of this transition in the history. Every transition is in
     * the citizen's history; only these show its reason as well (ADR-0020, "Citizen sees").
     */
    public boolean citizenSeesReason() {
        return reason == Reason.SHOWN_TO_CITIZEN;
    }
}
