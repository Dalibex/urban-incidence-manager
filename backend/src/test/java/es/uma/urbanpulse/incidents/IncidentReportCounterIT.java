package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest.ReportedLocation;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.service.IncidentReportService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reports counter that the load tests read in Grafana (#176, ADR-0023) counts only the reports
 * that are stored: one whose data is rejected, or whose transaction rolls back, is not counted.
 *
 * <p>The tests commit real transactions, so they delete their own rows afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncidentReportCounterIT {

    private static final String COUNTER = "urbanpulse.incidents.reported";

    @Autowired private IncidentReportService reports;
    @Autowired private MeterRegistry registry;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;

    private User citizen;

    @BeforeEach
    void freshCitizen() {
        var user = new User();
        user.setName("Counter citizen");
        user.setEmail("counter-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(Role.CITIZEN);
        user.setEnabled(true);
        citizen = users.save(user);
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        var reported = "SELECT id FROM incident WHERE reporter_id = ?";
        TestHistories.delete(jdbc, "incident_id IN (" + reported + ")", citizen.getId());
        jdbc.update(
                "DELETE FROM external_observation WHERE urban_context_id IN"
                        + " (SELECT id FROM urban_context WHERE incident_id IN ("
                        + reported
                        + "))",
                citizen.getId());
        jdbc.update(
                "DELETE FROM urban_context WHERE incident_id IN (" + reported + ")",
                citizen.getId());
        jdbc.update(
                "DELETE FROM location WHERE incident_id IN (" + reported + ")", citizen.getId());
        jdbc.update("DELETE FROM incident WHERE reporter_id = ?", citizen.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", citizen.getId());
    }

    @Test
    void aStoredReportIsCountedOnce() {
        double before = count();

        var incident = reports.createIncident(report(null), citizen.getId());

        assertThat(count()).isEqualTo(before + 1);
        // The report starts its urban context, empty, for the weather worker (ADR-0018).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM urban_context WHERE incident_id = ?",
                                String.class,
                                incident.getId()))
                .isEqualTo("PENDING");
    }

    @Test
    void aRejectedReportIsNotCounted() {
        double before = count();

        assertThatThrownBy(() -> reports.createIncident(report(UUID.randomUUID()), citizen.getId()))
                .isInstanceOf(InvalidIncidentDataException.class);

        assertThat(count()).isEqualTo(before);
    }

    // #289: a retry with the same Idempotency-Key stores nothing, so it is not counted again.
    @Test
    void aRetryWithTheSameKeyIsCountedOnce() {
        double before = count();
        UUID key = UUID.randomUUID();

        reports.createIncident(report(null), citizen.getId(), key);
        reports.createIncident(report(null), citizen.getId(), key);

        assertThat(count()).isEqualTo(before + 1);
    }

    @Test
    void aReportWhoseTransactionRollsBackIsNotCounted() {
        double before = count();

        transaction.executeWithoutResult(
                status -> {
                    reports.createIncident(report(null), citizen.getId());
                    status.setRollbackOnly();
                });

        assertThat(count()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM incident WHERE reporter_id = ?",
                                Integer.class,
                                citizen.getId()))
                .isZero();
    }

    private double count() {
        return registry.get(COUNTER).counter().count();
    }

    /** A report in Málaga; a category that does not exist makes the service reject it. */
    private static CreateIncidentRequest report(UUID categoryId) {
        return new CreateIncidentRequest(
                "Broken bench",
                "The bench in front of the school is broken.",
                categoryId,
                new ReportedLocation(36.7213, -4.4214, 8.0));
    }
}
