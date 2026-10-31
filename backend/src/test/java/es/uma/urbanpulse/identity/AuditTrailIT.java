package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.identity.service.AuditService;
import es.uma.urbanpulse.identity.service.AuditTarget;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail of #36: an entry and the change it records commit or roll back together, and
 * nothing can change an entry afterwards.
 *
 * <p>The tests commit real transactions, so they cannot roll back to clean up, and the trigger
 * would refuse a {@code DELETE} anyway: every test works on users and entries of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditTrailIT {

    @Autowired private AuditService audit;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private User technician;

    @BeforeEach
    void freshUsers() {
        admin = users.save(user(Role.ADMINISTRATOR));
        technician = users.save(user(Role.TECHNICIAN));
    }

    @Test
    void commitsTheEntryWithTheChange() {
        var start = Instant.now().truncatedTo(ChronoUnit.MICROS);

        transaction.executeWithoutResult(status -> changeTechnicianRole(admin.getId()));

        assertThat(roleOf(technician)).isEqualTo("MUNICIPAL_OPERATOR");
        assertThat(entriesAbout(technician)).isEqualTo(1);
        var entry =
                jdbc.queryForMap(
                        "SELECT actor_id, instant, action, target_type,"
                                + " details -> 'before' ->> 'role' AS before_role,"
                                + " details -> 'after' ->> 'role' AS after_role"
                                + " FROM audit_log WHERE target_id = ?",
                        technician.getId());
        assertThat(entry)
                .containsEntry("actor_id", admin.getId())
                .containsEntry("action", "CHANGE_ROLE")
                .containsEntry("target_type", "USER")
                .containsEntry("before_role", "TECHNICIAN")
                .containsEntry("after_role", "MUNICIPAL_OPERATOR");
        assertThat(((Timestamp) entry.get("instant")).toInstant()).isBetween(start, Instant.now());
    }

    @Test
    void rollsBackTheEntryWhenTheChangeFails() {
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            changeTechnicianRole(admin.getId());
                                            throw new IllegalStateException("change failed");
                                        }))
                .hasMessage("change failed");

        assertThat(roleOf(technician)).isEqualTo("TECHNICIAN");
        assertThat(entriesAbout(technician)).isZero();
    }

    @Test
    void rollsBackTheChangeWhenTheEntryFails() {
        var unknownActor = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> changeTechnicianRole(unknownActor)))
                .isInstanceOf(DataAccessException.class);

        assertThat(roleOf(technician)).isEqualTo("TECHNICIAN");
        assertThat(entriesAbout(technician)).isZero();
    }

    @Test
    void refusesToRecordOutsideATransaction() {
        assertThatThrownBy(
                        () ->
                                audit.record(
                                        admin.getId(),
                                        AuditAction.DISABLE_USER,
                                        AuditTarget.user(technician.getId()),
                                        Map.of("enabled", true),
                                        Map.of("enabled", false)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(entriesAbout(technician)).isZero();
    }

    @Test
    void recordsAFieldThatHadNoValueAsNull() {
        var incidentId = UUID.randomUUID();
        var before = new HashMap<String, Object>();
        before.put("categoryId", null);

        transaction.executeWithoutResult(
                status ->
                        audit.record(
                                admin.getId(),
                                AuditAction.CLASSIFY_INCIDENT,
                                AuditTarget.incident(incidentId),
                                before,
                                Map.of("categoryId", UUID.randomUUID())));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT details -> 'before' -> 'categoryId' = 'null'::jsonb"
                                        + " FROM audit_log WHERE target_id = ?",
                                Boolean.class,
                                incidentId))
                .isTrue();
    }

    @Test
    void refusesAValueThatIsNotAScalar() {
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                audit.record(
                                                        admin.getId(),
                                                        AuditAction.CREATE_USER,
                                                        AuditTarget.user(technician.getId()),
                                                        Map.of(),
                                                        Map.of("user", technician))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'user' must be a scalar");

        assertThat(entriesAbout(technician)).isZero();
    }

    @Test
    void theDatabaseRejectsUpdatingDeletingOrTruncatingEntries() {
        transaction.executeWithoutResult(status -> changeTechnicianRole(admin.getId()));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE audit_log SET action = 'CREATE_USER'"
                                                + " WHERE target_id = ?",
                                        technician.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: UPDATE");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM audit_log WHERE target_id = ?",
                                        technician.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: DELETE");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: TRUNCATE");

        assertThat(entriesAbout(technician)).isEqualTo(1);
    }

    /** A use case as #40 will write it: the change and its entry in the caller's transaction. */
    private void changeTechnicianRole(UUID actorId) {
        var user = users.findById(technician.getId()).orElseThrow();
        var before = user.getRole();
        user.setRole(Role.MUNICIPAL_OPERATOR);
        // Hibernate flushes inserts before updates: flushing here makes the UPDATE reach the
        // database before the entry, so a failing entry has a written change to roll back.
        users.saveAndFlush(user);
        audit.record(
                actorId,
                AuditAction.CHANGE_ROLE,
                AuditTarget.user(user.getId()),
                Map.of("role", before.name()),
                Map.of("role", user.getRole().name()));
    }

    private String roleOf(User user) {
        return jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, user.getId());
    }

    private int entriesAbout(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_id = ?", Integer.class, user.getId());
    }

    private static User user(Role role) {
        var user = new User();
        user.setName("Audit " + role);
        user.setEmail("audit-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }
}
