package es.uma.urbanpulse.incidents.mapper;

import es.uma.urbanpulse.incidents.dto.CategorySummary;
import es.uma.urbanpulse.incidents.dto.IncidentLocation;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.entity.Incident;
import org.locationtech.jts.geom.Point;

public final class IncidentMapper {

    private IncidentMapper() {}

    public static IncidentResponse toResponse(Incident incident) {
        var category = incident.getCategory();
        var location = incident.getLocation();
        Point point = location.getCoordinates();
        return new IncidentResponse(
                incident.getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getStatus(),
                incident.getPriority(),
                category == null ? null : new CategorySummary(category.getId(), category.getName()),
                new IncidentLocation(
                        point.getY(),
                        point.getX(),
                        location.getAccuracy(),
                        location.getAddress(),
                        location.getNeighborhood(),
                        location.getDistrict()),
                incident.getReportedAt(),
                incident.getUpdatedAt(),
                incident.getClosedAt());
    }
}
