package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.time.OffsetDateTime;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        String title,
        String description,
        IncidentStatus status,
        Priority priority,
        CategorySummary category,
        IncidentLocation location,
        OffsetDateTime reportedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt) {}
