package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.Priority;
import java.util.UUID;

/**
 * {@code UpdateIncidentRequest}: the body of {@code updateIncident}. Nothing is checked with Bean
 * Validation, which would run before the role check: {@code IncidentClassificationService} checks
 * every field after the role, the incident, the {@code version} and the status (ADR-0020).
 *
 * @param version the {@code version} of the incident the client read; {@code null} gets a {@code
 *     422} on this field
 * @param categoryId {@code null} to keep the category
 * @param priority {@code null} to keep the priority; an unknown value is a {@code 400}
 * @param priorityReason required with {@code priority}, refused without it
 */
public record UpdateIncidentRequest(
        Integer version, UUID categoryId, Priority priority, String priorityReason) {}
