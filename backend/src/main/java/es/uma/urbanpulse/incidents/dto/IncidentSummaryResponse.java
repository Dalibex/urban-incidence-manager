package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code IncidentSummaryResponse}: one incident in a listing. {@code priority} is staff-only, so a
 * citizen gets a type without it rather than a {@code null} (docs/api.md, "Absent and null").
 */
public sealed interface IncidentSummaryResponse {

    static IncidentSummaryResponse of(Incident incident, Role role) {
        return role == Role.CITIZEN ? Citizen.of(incident) : Staff.of(incident);
    }

    record Staff(
            UUID id,
            String title,
            IncidentStatus status,
            Priority priority,
            CategorySummary category,
            Instant reportedAt,
            Instant updatedAt)
            implements IncidentSummaryResponse {

        static Staff of(Incident incident) {
            return new Staff(
                    incident.getId(),
                    incident.getTitle(),
                    incident.getStatus(),
                    incident.getPriority(),
                    CategorySummary.of(incident.getCategory()),
                    incident.getReportedAt().toInstant(),
                    incident.getUpdatedAt().toInstant());
        }
    }

    record Citizen(
            UUID id,
            String title,
            IncidentStatus status,
            CategorySummary category,
            Instant reportedAt,
            Instant updatedAt)
            implements IncidentSummaryResponse {

        static Citizen of(Incident incident) {
            return new Citizen(
                    incident.getId(),
                    incident.getTitle(),
                    incident.getStatus(),
                    CategorySummary.of(incident.getCategory()),
                    incident.getReportedAt().toInstant(),
                    incident.getUpdatedAt().toInstant());
        }
    }
}
