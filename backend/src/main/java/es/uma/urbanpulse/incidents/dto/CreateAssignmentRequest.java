package es.uma.urbanpulse.incidents.dto;

import java.util.UUID;

/**
 * {@code CreateAssignmentRequest}: the body of {@code createIncidentAssignment}. Nothing is checked
 * with Bean Validation, which would run before the role check: the {@code version} is checked by
 * {@code IncidentLifecycleService}, and the department, the technician and the team by the domain,
 * in the order of ADR-0020.
 *
 * @param version the {@code version} of the incident the client read; {@code null} gets a {@code
 *     422} on this field
 * @param departmentId required by the domain; {@code null} gets a {@code 422} on this field
 * @param technicianId {@code null} to assign the incident to the department only
 * @param team {@code null} when not given
 */
public record CreateAssignmentRequest(
        Integer version, UUID departmentId, UUID technicianId, String team) {}
