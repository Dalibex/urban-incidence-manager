package es.uma.urbanpulse.geospatial.repository;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * A location whose address is {@code PENDING}, with what the worker needs to resolve it: its
 * position (WGS 84, in degrees) and when its incident was reported.
 */
public record PendingAddress(
        UUID locationId, double latitude, double longitude, OffsetDateTime reportedAt) {

    /** For the query: a JTS point has the longitude as x and the latitude as y. */
    public PendingAddress(UUID locationId, Point position, OffsetDateTime reportedAt) {
        this(locationId, position.getY(), position.getX(), reportedAt);
    }
}
