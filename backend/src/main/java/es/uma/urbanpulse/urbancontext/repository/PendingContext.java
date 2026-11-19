package es.uma.urbanpulse.urbancontext.repository;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * A {@code PENDING} context with what the worker needs to enrich it: the position of its incident
 * (WGS 84, in degrees) and when it was reported.
 */
public record PendingContext(
        UUID id, double latitude, double longitude, OffsetDateTime reportedAt) {

    /** For the query: a JTS point has the longitude as x and the latitude as y. */
    public PendingContext(UUID id, Point position, OffsetDateTime reportedAt) {
        this(id, position.getY(), position.getX(), reportedAt);
    }
}
