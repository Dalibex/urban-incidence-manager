package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.incidents.repository.StatusChangeRepository;
import es.uma.urbanpulse.notifications.service.CitizenNotifications;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T4 of #376, "processed twice": handing the same status change to {@link CitizenNotifications}
 * twice leaves one notification per channel and never throws, so it can never roll back the status
 * change it runs in (the plan of #373, section 2).
 *
 * <p>The change is inserted with SQL, so no notification exists before the test calls the service.
 * The tests commit, so the unique key of the table is really checked; the rows are deleted after
 * each test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("CitizenNotifications, processing the same change twice")
class CitizenNotificationsIT {

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant VALIDATED_AT = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired private CitizenNotifications notifications;
    @Autowired private StatusChangeRepository statusChanges;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;

    private UUID citizen;
    private UUID operator;
    private UUID incident;
    private UUID validation;

    @BeforeEach
    void validateAnIncidentOfACitizenWithBothChannels() {
        citizen = user("CITIZEN");
        operator = user("MUNICIPAL_OPERATOR");
        jdbc.update(
                "UPDATE users SET notify_by_push = true, notify_by_email = true WHERE id = ?",
                citizen);
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                citizen,
                "ExponentPushToken[" + UUID.randomUUID() + "]",
                Timestamp.from(REPORTED_AT),
                Timestamp.from(REPORTED_AT));
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'VALIDATED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(VALIDATED_AT));
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, visible_to_citizen) VALUES (?, ?, ?, NULL, 'REPORTED', true)",
                incident,
                citizen,
                Timestamp.from(REPORTED_AT));
        validation =
                jdbc.queryForObject(
                        "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                                + " new_status, visible_to_citizen)"
                                + " VALUES (?, ?, ?, 'REPORTED', 'VALIDATED', true) RETURNING id",
                        UUID.class,
                        incident,
                        operator,
                        Timestamp.from(VALIDATED_AT));
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        TestHistories.delete(jdbc, "incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM push_token WHERE user_id = ?", citizen);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", citizen, operator);
    }

    @Test
    @DisplayName("twice in one transaction: one row per channel, and no exception")
    void twiceInOneTransactionLeavesOneRowPerChannel() {
        // Arrange: the validation inserted above, with no notification yet.

        // Act
        Throwable thrown =
                catchThrowable(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            var change =
                                                    statusChanges
                                                            .findById(validation)
                                                            .orElseThrow();
                                            notifications.statusChanged(change);
                                            notifications.statusChanged(change);
                                        }));

        // Assert
        assertThat(thrown).isNull();
        assertThat(channelsOfTheValidation()).containsExactly("EMAIL", "PUSH");
    }

    @Test
    @DisplayName("again after the first one committed: one row per channel, and no exception")
    void againAfterTheFirstCommittedLeavesOneRowPerChannel() {
        // Arrange
        processTheValidation();

        // Act
        Throwable thrown = catchThrowable(this::processTheValidation);

        // Assert
        assertThat(thrown).isNull();
        assertThat(channelsOfTheValidation()).containsExactly("EMAIL", "PUSH");
    }

    private void processTheValidation() {
        transaction.executeWithoutResult(
                status ->
                        notifications.statusChanged(
                                statusChanges.findById(validation).orElseThrow()));
    }

    private List<String> channelsOfTheValidation() {
        return jdbc.queryForList(
                "SELECT channel FROM notification WHERE status_change_id = ? ORDER BY channel",
                String.class,
                validation);
    }

    private UUID user(String role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test " + role,
                role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role);
    }
}
