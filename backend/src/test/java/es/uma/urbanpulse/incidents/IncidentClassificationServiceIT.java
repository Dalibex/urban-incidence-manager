package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.dto.UpdateIncidentRequest;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.service.IncidentClassificationService;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T9 of #70: two operators classify the same incident at once, and only the first change is kept,
 * with its audit entry and nothing of the second.
 *
 * <p>Like {@code IncidentLifecycleServiceIT}, the tests do not run inside a transaction, so the
 * checks read what the database really committed, and the concurrent change is made deterministic
 * without threads: another connection commits between the read and the save. The incidents are
 * deleted after each test; the users and the audit entries stay, because the audit trail is
 * append-only and its entries keep their actor.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncidentClassificationServiceIT {

    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    private static final String REASON = "The street is dark near a school.";

    @Autowired private IncidentClassificationService classification;
    @Autowired private IncidentRepository incidents;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private UUID operator;
    private UUID otherOperator;
    private UUID category;
    private UUID incident;

    @BeforeEach
    void createTheData() {
        operator = user(Role.MUNICIPAL_OPERATOR);
        otherOperator = user(Role.MUNICIPAL_OPERATOR);
        category =
                jdbc.queryForObject(
                        "SELECT id FROM category WHERE is_active ORDER BY name LIMIT 1",
                        UUID.class);
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'REPORTED', ?, ?) RETURNING id",
                        UUID.class,
                        user(Role.CITIZEN),
                        Timestamp.from(MONDAY),
                        Timestamp.from(MONDAY));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
    }

    @AfterEach
    void deleteTheIncident() {
        jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
    }

    // T9: both operators read the same version; the second one gets 409.
    @Test
    void ofTwoOperatorsClassifyingTheSameVersionOnlyTheFirstOneSucceeds() {
        int read = storedVersion();

        classify(operator, read, Priority.HIGH);
        assertThatThrownBy(() -> classify(otherOperator, read, Priority.LOW))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(storedPriority()).isEqualTo("HIGH");
        assertThat(auditActors()).isEqualTo(1);
    }

    // T9: the other operator commits while this classification is being saved.
    @Test
    void aClassificationThatFailsWhileSavingLeavesNoAuditEntry() {
        int read = storedVersion();

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            // The incident is read in this transaction at `read`...
                                            incidents.findById(incident).orElseThrow();
                                            // ...another operator commits a change meanwhile...
                                            changeFromAnotherConnection();
                                            // ...and this classification is saved on what it read.
                                            classify(otherOperator, read, Priority.LOW);
                                        }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(storedPriority()).isNull();
        assertThat(auditActors()).isZero();
    }

    private void classify(UUID caller, int version, Priority priority) {
        classification.classify(
                incident,
                new UpdateIncidentRequest(version, category, priority, REASON),
                caller,
                Role.MUNICIPAL_OPERATOR);
    }

    private void changeFromAnotherConnection() {
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE incident SET version = version + 1 WHERE id = ?")) {
            connection.setAutoCommit(true);
            update.setObject(1, incident);
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?,"
                        + " '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    private int storedVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM incident WHERE id = ?", Integer.class, incident);
    }

    private String storedPriority() {
        return jdbc.queryForObject(
                "SELECT priority FROM incident WHERE id = ?", String.class, incident);
    }

    private int auditActors() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'CLASSIFY_INCIDENT' AND target_id = ?",
                Integer.class,
                incident);
    }
}
