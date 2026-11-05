package es.uma.urbanpulse.incidents.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * {@code CreateAssignmentRequest}: the body of {@code createIncidentAssignment}. Only {@code
 * version} is checked here, because the conflict check needs it. The department, the technician and
 * the team are the data of the transition and are checked by the domain after it, in the order of
 * ADR-0020.
 *
 * @param version the {@code version} of the incident the client read
 * @param departmentId required by the domain; {@code null} gets a {@code 422} on this field
 * @param technicianId {@code null} to assign the incident to the department only
 * @param team {@code null} when not given
 */
public record CreateAssignmentRequest(
        @NotNull @PositiveOrZero Integer version,
        UUID departmentId,
        UUID technicianId,
        String team) {}
