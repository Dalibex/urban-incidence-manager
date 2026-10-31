package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.Location;

/**
 * {@code IncidentLocation}: WGS 84 position in decimal degrees and what is known about the place.
 * {@code null} means "not available", never "none".
 */
public record IncidentLocationResponse(
        double latitude,
        double longitude,
        Double accuracy,
        String address,
        String neighborhood,
        String district) {

    static IncidentLocationResponse of(Location location) {
        // A JTS point keeps x = longitude, y = latitude.
        var point = location.getCoordinates();
        return new IncidentLocationResponse(
                point.getY(),
                point.getX(),
                location.getAccuracy(),
                location.getAddress(),
                location.getNeighborhood(),
                location.getDistrict());
    }
}
