package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.dto.CreateAssignmentRequest;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.service.IncidentAssignmentService;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * T9 and T13 of #72: what {@link IncidentAssignmentService} keeps when two operators assign the
 * same incident, or when saving a reassignment fails.
 *
 * <p>Like {@code IncidentLifecycleServiceIT}, the tests do not run inside a transaction, so the
 * checks read what the database really committed, and the concurrent change is made deterministic
 * without threads: another connection commits between the read and the save. The rows of each test
 * are deleted after it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncidentAssignmentServiceIT {

    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");

    @Autowired private IncidentAssignmentService assignments;
    @Autowired private IncidentRepository incidents;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final List<UUID> users = new ArrayList<>();
    private UUID operator;
    private UUID otherOperator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID department;
    private UUID incident;

    @BeforeEach
    void createTheData() {
        operator = user(Role.MUNICIPAL_OPERATOR);
        otherOperator = user(Role.MUNICIPAL_OPERATOR);
        technician = user(Role.TECHNICIAN);
        otherTechnician = user(Role.TECHNICIAN);
        department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Department " + UUID.randomUUID());
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'VALIDATED', ?, ?) RETURNING id",
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
    void deleteTheRowsOfTheTest() {
        jdbc.update("DELETE FROM status_change WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM assignment WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
        users.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
    }

    // T9: both operators read the same version; the second one gets 409.
    @Test
    void ofTwoOperatorsAssigningTheSameVersionOnlyTheFirstOneSucceeds() {
        int read = storedVersion();

        assign(operator, read, technician);
        assertThatThrownBy(() -> assign(otherOperator, read, otherTechnician))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(activeTechnicians()).containsExactly(technician);
        assertThat(storedHistory()).containsExactly("ASSIGNED");
    }

    // T9 and T13: the other operator commits while this reassignment is being saved.
    @Test
    void aReassignmentThatFailsWhileSavingKeepsThePreviousAssignment() {
        int read = assign(operator, storedVersion(), technician);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            // The incident is read in this transaction at `read`...
                                            incidents.findById(incident).orElseThrow();
                                            // ...another operator commits a change meanwhile...
                                            changeFromAnotherConnection();
                                            // ...and this reassignment is saved on what it read.
                                            assign(otherOperator, read, otherTechnician);
                                        }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(activeTechnicians()).containsExactly(technician);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM assignment WHERE incident_id = ?",
                                Long.class,
                                incident))
                .isOne();
        assertThat(storedHistory()).containsExactly("ASSIGNED");
        assertThat(storedStatus()).isEqualTo("ASSIGNED");
    }

    /** Assigns as this operator and returns the new version. */
    private int assign(UUID caller, int version, UUID assignedTechnician) {
        assignments.assign(
                incident,
                new CreateAssignmentRequest(version, department, assignedTechnician, null),
                caller,
                Role.MUNICIPAL_OPERATOR);
        return storedVersion();
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
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?,"
                                + " '{bcrypt}not-used', ?) RETURNING id",
                        UUID.class,
                        "Test " + role,
                        role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                        role.name());
        users.add(id);
        return id;
    }

    private List<UUID> activeTechnicians() {
        return jdbc.queryForList(
                "SELECT technician_id FROM assignment WHERE incident_id = ? AND is_active",
                UUID.class,
                incident);
    }

    private List<String> storedHistory() {
        return jdbc.queryForList(
                "SELECT new_status FROM status_change WHERE incident_id = ? ORDER BY instant",
                String.class,
                incident);
    }

    private int storedVersion() {
        return jdbc.queryForObject(
                "SELECT version FROM incident WHERE id = ?", Integer.class, incident);
    }

    private String storedStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM incident WHERE id = ?", String.class, incident);
    }
}
