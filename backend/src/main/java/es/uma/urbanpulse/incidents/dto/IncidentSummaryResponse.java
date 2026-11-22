package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.AddressStatus;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code IncidentSummaryResponse}: one incident in a listing. {@code priority}, {@code address} and
 * {@code addressStatus} are staff-only, so a citizen gets a type without them rather than a {@code
 * null} (docs/api.md, "Absent and null"). The address lets a technician find the incident without
 * reading the map (#375).
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
            String address,
            AddressStatus addressStatus,
            Instant reportedAt,
            Instant updatedAt)
            implements IncidentSummaryResponse {

        static Staff of(Incident incident) {
            var address = IncidentLocationResponse.AddressView.of(incident.getLocation());
            return new Staff(
                    incident.getId(),
                    incident.getTitle(),
                    incident.getStatus(),
                    incident.getPriority(),
                    CategorySummary.of(incident.getCategory()),
                    address.address(),
                    address.status(),
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
