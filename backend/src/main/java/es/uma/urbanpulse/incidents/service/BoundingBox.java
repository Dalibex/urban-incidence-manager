package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.domain.Specification;

/**
 * The bounding box of {@code listIncidentLocations} ({@code docs/api/openapi.yml}): WGS 84 limits
 * in decimal degrees, edges included. A box across the antimeridian is not supported, because the
 * service covers Málaga, so the minimum of each axis may not be over its maximum.
 *
 * @param minLatitude south limit
 * @param minLongitude west limit
 * @param maxLatitude north limit
 * @param maxLongitude east limit
 */
public record BoundingBox(
        double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {

    private static final double MAX_LATITUDE = 90;
    private static final double MAX_LONGITUDE = 180;

    /**
     * @throws MalformedRequestException if a limit is out of range or not a number, or a minimum is
     *     over its maximum
     */
    public BoundingBox {
        requireInRange("minLatitude", minLatitude, MAX_LATITUDE);
        requireInRange("minLongitude", minLongitude, MAX_LONGITUDE);
        requireInRange("maxLatitude", maxLatitude, MAX_LATITUDE);
        requireInRange("maxLongitude", maxLongitude, MAX_LONGITUDE);
        if (minLatitude > maxLatitude) {
            throw new MalformedRequestException("minLatitude must not be over maxLatitude.");
        }
        if (minLongitude > maxLongitude) {
            throw new MalformedRequestException("minLongitude must not be over maxLongitude.");
        }
    }

    /**
     * The box of a request, whose four limits are optional query parameters.
     *
     * @return empty when none of the four is used
     * @throws MalformedRequestException if only some of the four are used, or {@link
     *     #BoundingBox(double, double, double, double)} rejects them
     */
    public static Optional<BoundingBox> fromLimits(
            Double minLatitude, Double minLongitude, Double maxLatitude, Double maxLongitude) {
        var used =
                Stream.of(minLatitude, minLongitude, maxLatitude, maxLongitude)
                        .filter(limit -> limit != null)
                        .count();
        if (used == 0) {
            return Optional.empty();
        }
        if (used < 4) {
            throw new MalformedRequestException(
                    "The bounding box needs minLatitude, minLongitude, maxLatitude and maxLongitude,"
                            + " or none of them.");
        }
        return Optional.of(new BoundingBox(minLatitude, minLongitude, maxLatitude, maxLongitude));
    }

    /**
     * The incidents whose position is inside the box. The geography is read as geometry, so the
     * edges are the parallels and meridians of the limits, not great circles.
     */
    Specification<Incident> specification() {
        return (incident, query, cb) -> {
            var position =
                    cb.function(
                            "geometry", Object.class, incident.get("location").get("coordinates"));
            // ST_X is the longitude and ST_Y the latitude.
            var longitude = cb.function("ST_X", Double.class, position);
            var latitude = cb.function("ST_Y", Double.class, position);
            return cb.and(
                    cb.between(latitude, minLatitude, maxLatitude),
                    cb.between(longitude, minLongitude, maxLongitude));
        };
    }

    // The negated check also rejects NaN, which Spring parses from "?minLatitude=NaN".
    private static void requireInRange(String name, double value, double limit) {
        if (!(value >= -limit && value <= limit)) {
            throw new MalformedRequestException(
                    name + " must be between " + -limit + " and " + limit + ".");
        }
    }
}
