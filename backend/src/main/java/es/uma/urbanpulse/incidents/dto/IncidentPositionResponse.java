package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.util.UUID;

/**
 * {@code IncidentPosition}: an incident on the map, with its WGS 84 position in decimal degrees.
 * The side panel reads the rest with {@code getIncident}.
 *
 * @param priority {@code null} while the incident has no priority
 */
public record IncidentPositionResponse(
        UUID id, IncidentStatus status, Priority priority, double latitude, double longitude) {}
