package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import es.uma.urbanpulse.identity.entity.AuditTargetType;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.AuditLogRepository;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Location;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.repository.LocationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks that the entities of #20 really write and read their columns, which {@code
 * ddl-auto=validate} does not cover: it only compares column names and types.
 */
class EntityMappingIT extends PersistenceTestSupport {

    private static final OffsetDateTime REPORTED_AT =
            OffsetDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.UTC);

    // Calle Larios, Malaga. The column stores (longitude, latitude) in EPSG:4326.
    private static final double LONGITUDE = -4.4214;
    private static final double LATITUDE = 36.7213;

    @Autowired private IncidentRepository incidents;
    @Autowired private LocationRepository locations;
    @Autowired private AuditLogRepository auditLogs;

    @Test
    void storesAndReadsBackAnIncidentWithItsReporter() {
        Incident incident = incidentReportedBy(citizen());

        Incident saved = incidents.save(incident);
        flushAndClear();

        Incident found = incidents.findById(saved.getId()).orElseThrow();
        assertThat(found.getTitle()).isEqualTo("Broken streetlight");
        assertThat(found.getStatus()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(found.getReportedAt()).isEqualTo(REPORTED_AT);
        assertThat(found.getReporter().getEmail()).isEqualTo(incident.getReporter().getEmail());
    }

    /**
     * The report writes its own location; the test replaces the stored coordinates with SQL, so the
     * read-back goes through the stored column alone.
     */
    @Test
    void readsAStoredPointAsLongitudeAndLatitude() {
        UUID incidentId = incidents.save(incidentReportedBy(citizen())).getId();
        flushAndClear();

        UUID locationId =
                (UUID)
                        entityManager
                                .createNativeQuery("SELECT id FROM location WHERE incident_id = ?")
                                .setParameter(1, incidentId)
                                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "UPDATE location SET coordinates ="
                                + " ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography WHERE id = ?")
                .setParameter(1, LONGITUDE)
                .setParameter(2, LATITUDE)
                .setParameter(3, locationId)
                .executeUpdate();
        flushAndClear();

        Location found = locations.findById(locationId).orElseThrow();

        // A JTS Point prints as WKT (x = longitude, y = latitude); raw EWKB bytes print as
        // "[B@...".
        assertThat(String.valueOf(found.getCoordinates()))
                .isEqualTo("POINT (" + LONGITUDE + " " + LATITUDE + ")");
    }

    @Test
    void storesAndReadsBackJsonbDetails() {
        User actor = citizen();
        AuditLog entry =
                new AuditLog(
                        actor,
                        REPORTED_AT,
                        AuditAction.DISABLE_USER,
                        AuditTargetType.USER,
                        actor.getId(),
                        "{\"reason\": \"spam\"}");

        AuditLog saved = auditLogs.save(entry);
        flushAndClear();

        assertThat(
                        queryNative(
                                "SELECT details ->> 'reason' FROM audit_log WHERE id = ?",
                                String.class,
                                saved.getId()))
                .isEqualTo("spam");
        assertThat(auditLogs.findById(saved.getId()).orElseThrow().getDetails())
                .isEqualTo("{\"reason\": \"spam\"}");
    }

    private User citizen() {
        User user = new User();
        user.setName("Ana Citizen");
        user.setEmail("citizen-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.CITIZEN);
        entityManager.persist(user);
        return user;
    }

    private static Incident incidentReportedBy(User reporter) {
        return Incident.report(
                reporter,
                "Broken streetlight",
                "The streetlight at the corner is off.",
                null,
                locationAt(36.7213, -4.4214),
                REPORTED_AT.toInstant());
    }

    private static Location locationAt(double latitude, double longitude) {
        Location location = new Location();
        location.setCoordinates(
                new GeometryFactory().createPoint(new Coordinate(longitude, latitude)));
        return location;
    }
}
