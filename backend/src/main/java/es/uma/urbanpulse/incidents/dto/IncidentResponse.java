package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code IncidentResponse}: the full incident. {@code priority} is staff-only, so a citizen gets a
 * type without it rather than a {@code null} (docs/api.md, "Absent and null").
 */
public sealed interface IncidentResponse {

    static IncidentResponse of(Incident incident, Location location, Role role) {
        return role == Role.CITIZEN ? Citizen.of(incident, location) : Staff.of(incident, location);
    }

    record Staff(
            UUID id,
            String title,
            String description,
            IncidentStatus status,
            Priority priority,
            CategorySummary category,
            IncidentLocationResponse location,
            Instant reportedAt,
            Instant updatedAt,
            Instant closedAt)
            implements IncidentResponse {

        static Staff of(Incident incident, Location location) {
            return new Staff(
                    incident.getId(),
                    incident.getTitle(),
                    incident.getDescription(),
                    incident.getStatus(),
                    incident.getPriority(),
                    CategorySummary.of(incident.getCategory()),
                    IncidentLocationResponse.of(location),
                    incident.getReportedAt().toInstant(),
                    incident.getUpdatedAt().toInstant(),
                    instantOrNull(incident.getClosedAt()));
        }
    }

    record Citizen(
            UUID id,
            String title,
            String description,
            IncidentStatus status,
            CategorySummary category,
            IncidentLocationResponse location,
            Instant reportedAt,
            Instant updatedAt,
            Instant closedAt)
            implements IncidentResponse {

        static Citizen of(Incident incident, Location location) {
            return new Citizen(
                    incident.getId(),
                    incident.getTitle(),
                    incident.getDescription(),
                    incident.getStatus(),
                    CategorySummary.of(incident.getCategory()),
                    IncidentLocationResponse.of(location),
                    incident.getReportedAt().toInstant(),
                    incident.getUpdatedAt().toInstant(),
                    instantOrNull(incident.getClosedAt()));
        }
    }

    private static Instant instantOrNull(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
