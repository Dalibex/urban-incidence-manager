package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.identity.entity.User;

/**
 * The data a transition receives besides the actor. Which items are required depends on the
 * transition (ADR-0020, "Required data"); {@link Incident#changeStatus} checks them.
 *
 * @param reason reason, message to the citizen, or resolution notes; {@code null} when not given
 * @param department department of a new assignment; {@code null} for other transitions
 * @param technician technician of a new assignment; {@code null} when it is assigned to the
 *     department only, or for other transitions
 * @param team free-text team of a new assignment; {@code null} when not given
 */
public record TransitionDetails(
        String reason, Department department, User technician, String team) {

    private static final TransitionDetails NONE = new TransitionDetails(null, null, null, null);

    public static TransitionDetails none() {
        return NONE;
    }

    public static TransitionDetails withReason(String reason) {
        return new TransitionDetails(reason, null, null, null);
    }

    /** Assignment to a department and, optionally, to one of its technicians. */
    public static TransitionDetails assignment(Department department, User technician) {
        return assignment(department, technician, null);
    }

    /**
     * Assignment to a department and, optionally, to one of its technicians and a team.
     *
     * @param team {@code null} when not given
     */
    public static TransitionDetails assignment(
            Department department, User technician, String team) {
        return new TransitionDetails(null, department, technician, team);
    }
}
