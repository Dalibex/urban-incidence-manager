package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.incidents.entity.Assignment;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code AssignmentResponse}: who is responsible for an incident. Staff only: a citizen never gets
 * the department, the technician or the team (ADR-0020, "Citizen sees").
 *
 * @param technician {@code null} when the incident is assigned to the department only
 * @param team {@code null} when there is no team
 */
public record AssignmentResponse(
        UUID id,
        DepartmentResponse department,
        Technician technician,
        String team,
        Instant assignedAt) {

    /** {@code TechnicianSummary}: the technician of the assignment. */
    public record Technician(UUID id, String name) {

        static Technician of(User user) {
            return user == null ? null : new Technician(user.getId(), user.getName());
        }
    }

    public static AssignmentResponse of(Assignment assignment) {
        return new AssignmentResponse(
                assignment.getId(),
                DepartmentResponse.of(assignment.getDepartment()),
                Technician.of(assignment.getTechnician()),
                assignment.getTeam(),
                assignment.getAssignedAt().toInstant());
    }
}
