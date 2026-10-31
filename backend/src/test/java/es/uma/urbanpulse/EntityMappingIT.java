package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.identity.entity.AuditLog;
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

    @Test
    void readsAStoredPointAsLongitudeAndLatitude() {
        Incident incident = incidents.save(incidentReportedBy(citizen()));
        UUID locationId = UUID.randomUUID();
        entityManager
                .createNativeQuery(
                        "INSERT INTO location (id, incident_id, coordinates) VALUES (?, ?,"
                                + " ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)")
                .setParameter(1, locationId)
                .setParameter(2, incident.getId())
                .setParameter(3, LONGITUDE)
                .setParameter(4, LATITUDE)
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
        AuditLog entry = new AuditLog();
        entry.setActor(actor);
        entry.setInstant(REPORTED_AT);
        entry.setAction("USER_DISABLED");
        entry.setDetails("{\"reason\": \"spam\"}");

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
                REPORTED_AT.toInstant());
    }
}
