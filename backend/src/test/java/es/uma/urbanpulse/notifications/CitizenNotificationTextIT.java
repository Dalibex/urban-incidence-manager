package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.incidents.repository.StatusChangeRepository;
import es.uma.urbanpulse.notifications.service.CitizenNotifications;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #373: the text of the notification of a status change. It names the incident and its new status;
 * an information request (reported to reported) asks for more information instead, with its reason.
 * The reason goes in only where the citizen already sees it in the history (ADR-0020): an
 * information request and a rejection.
 *
 * <p>The change is inserted with SQL and handed to {@link CitizenNotifications}; the citizen has
 * only email on, as after sign-up, so the change leaves one row. The rows are deleted after each
 * test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("CitizenNotifications, the text of the notification")
class CitizenNotificationTextIT {

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired private CitizenNotifications notifications;
    @Autowired private StatusChangeRepository statusChanges;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;

    private UUID citizen;
    private UUID operator;
    private UUID incident;

    @BeforeEach
    void createAnIncidentOfACitizen() {
        citizen = user("CITIZEN");
        operator = user("MUNICIPAL_OPERATOR");
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'REPORTED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(REPORTED_AT));
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        TestHistories.delete(jdbc, "incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", citizen, operator);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            nullValues = "null",
            value = {
                "REPORTED | REPORTED  | Add a photo of the lamp. | Your incident \"Broken streetlight\""
                        + " needs more information: Add a photo of the lamp.",
                "REPORTED | REJECTED  | Not a municipal matter.  | Your incident \"Broken streetlight\""
                        + " is now rejected. Message: Not a municipal matter.",
                "REPORTED | VALIDATED | Checked by phone.        | Your incident \"Broken streetlight\""
                        + " is now validated.",
                "ASSIGNED | IN_PROGRESS | null                   | Your incident \"Broken streetlight\""
                        + " is now in progress."
            })
    @DisplayName("names the new status, or asks for information, with the reason the citizen sees")
    void namesTheStatusOrAsksForInformation(
            String previous, String next, String reason, String expected) {
        // Arrange
        UUID change = statusChange(previous, next, reason);

        // Act
        transaction.executeWithoutResult(
                status ->
                        notifications.statusChanged(statusChanges.findById(change).orElseThrow()));

        // Assert
        assertThat(messageOf(change)).isEqualTo(expected);
    }

    private UUID statusChange(String previous, String next, String reason) {
        return jdbc.queryForObject(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, reason, visible_to_citizen)"
                        + " VALUES (?, ?, ?, ?, ?, ?, true) RETURNING id",
                UUID.class,
                incident,
                operator,
                Timestamp.from(CHANGED_AT),
                previous,
                next,
                reason);
    }

    private String messageOf(UUID change) {
        return jdbc.queryForObject(
                "SELECT message FROM notification WHERE status_change_id = ? AND channel = 'EMAIL'",
                String.class,
                change);
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
