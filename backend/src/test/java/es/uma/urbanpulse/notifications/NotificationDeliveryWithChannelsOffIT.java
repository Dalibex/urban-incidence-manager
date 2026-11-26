package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.notifications.service.NotificationDeliveryWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #373 with the test configuration as it is: the scheduled delivery job is off ({@code
 * urbanpulse.notifications.enabled=false}) and so are both channels ({@code push-enabled}, {@code
 * email-enabled}), whose base URLs are a closed port of the loopback interface. A channel that is
 * off is handled by the logging sender of the plan (section 3), which sends nothing and ends the
 * row {@code DELIVERED}; a call to a provider would be a refused connection and leave it pending.
 *
 * <p>The interval is a fifth of a second here, so a test can wait past several intervals and check
 * that the switch, not the interval, keeps the job from running (F4 of the plan).
 */
@SpringBootTest(properties = "urbanpulse.notifications.interval=PT0.2S")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("NotificationDeliveryWorker with the channels and the scheduled job off")
class NotificationDeliveryWithChannelsOffIT {

    private static final String MESSAGE = "Your incident \"Broken streetlight\" is now validated.";
    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    // Five intervals of the scheduled job.
    private static final Duration PAST_SEVERAL_INTERVALS = Duration.ofSeconds(1);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationDeliveryWorker worker;
    @Autowired private Clock clock;

    private UUID citizen;
    private String citizenEmail;
    private String token;
    private UUID incident;

    @BeforeEach
    void createACitizenWithADeviceAndAnIncident() {
        citizenEmail = "citizen-" + UUID.randomUUID() + "@example.com";
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN')"
                                + " RETURNING id",
                        UUID.class,
                        citizenEmail);
        token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                citizen,
                token,
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
                        Timestamp.from(REPORTED_AT));
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        TestHistories.delete(jdbc, "incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM push_token WHERE user_id = ?", citizen);
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    @Nested
    @DisplayName("A run of the worker")
    class ARun {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PUSH", "EMAIL"})
        @DisplayName("ends a row of a channel that is off DELIVERED, without calling its provider")
        void endsTheRowDeliveredWithoutCallingTheProvider(String channel) {
            // Arrange
            UUID notification = pendingNotification(channel);

            // Act
            worker.deliverPending();

            // Assert
            Map<String, Object> row = notification(notification);
            assertThat(row).containsEntry("delivery_status", "DELIVERED");
            assertThat(row.get("sent_at")).isNotNull();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PUSH", "EMAIL"})
        @DisplayName("logs the channel and the notification id, never the text, address or token")
        void logsOnlyTheChannelAndTheId(String channel, CapturedOutput output) {
            // Arrange
            UUID notification = pendingNotification(channel);

            // Act
            worker.deliverPending();

            // Assert
            assertThat(output.getAll().lines())
                    .anyMatch(
                            line ->
                                    line.contains(channel)
                                            && line.contains(notification.toString()));
            assertThat(output.getAll())
                    .doesNotContain(MESSAGE)
                    .doesNotContain(citizenEmail)
                    .doesNotContain(token);
        }
    }

    /**
     * A write of an outcome can fail for one row (a lost connection, a constraint). The batch was
     * already sent, so the other rows must still end {@code DELIVERED}; left {@code PENDING}, the
     * next run would send them again. A test trigger refuses the DELIVERED write of one row, the
     * oldest, so the worker meets it first; a FAILED write of that row goes through.
     */
    @Nested
    @DisplayName("When the write of one outcome fails")
    class WhenOneWriteFails {

        @AfterEach
        void dropTheTrigger() {
            jdbc.execute("DROP TRIGGER IF EXISTS test_refuse_write ON notification");
            jdbc.execute("DROP FUNCTION IF EXISTS test_refuse_write()");
        }

        @Test
        @DisplayName("the other rows of the batch still end DELIVERED")
        void theOtherRowsStillEndDelivered() {
            // Arrange
            UUID refused = pendingNotification("EMAIL", 3);
            List<UUID> others =
                    List.of(pendingNotification("EMAIL", 2), pendingNotification("EMAIL", 1));
            refuseTheWriteOf(refused);

            // Act
            // Whether the run reports the failure is not part of this behaviour; the rows are.
            catchThrowable(() -> worker.deliverPending());

            // Assert
            assertThat(others)
                    .map(id -> notification(id).get("delivery_status"))
                    .containsExactly("DELIVERED", "DELIVERED");
        }

        @Test
        @DisplayName("the refused row ends FAILED after one attempt, so no run sends it again")
        void theRefusedRowEndsFailed() {
            // Arrange
            UUID refused = pendingNotification("EMAIL", 3);
            pendingNotification("EMAIL", 2);
            refuseTheWriteOf(refused);

            // Act
            catchThrowable(() -> worker.deliverPending());

            // Assert
            Map<String, Object> row = notification(refused);
            assertThat(row).containsEntry("delivery_status", "FAILED").containsEntry("attempts", 1);
            assertThat(row.get("sent_at")).isNotNull();
        }

        private void refuseTheWriteOf(UUID notification) {
            jdbc.execute(
                    """
                    CREATE FUNCTION test_refuse_write() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF OLD.id = '%s' AND NEW.delivery_status = 'DELIVERED' THEN
                            RAISE EXCEPTION 'write refused by the test';
                        END IF;
                        RETURN NEW;
                    END $$
                    """
                            .formatted(notification));
            jdbc.execute(
                    "CREATE TRIGGER test_refuse_write BEFORE UPDATE ON notification"
                            + " FOR EACH ROW EXECUTE FUNCTION test_refuse_write()");
        }
    }

    // F4 of the plan: the switch guards the scheduled runs.
    @Nested
    @DisplayName("With the scheduled job off")
    class WithTheScheduledJobOff {

        @Test
        @DisplayName("a due PENDING row is still PENDING after several intervals")
        void aDueRowStaysPending() throws InterruptedException {
            // Arrange
            UUID notification = pendingNotification("EMAIL");

            // Act
            Thread.sleep(PAST_SEVERAL_INTERVALS.toMillis());

            // Assert
            assertThat(notification(notification))
                    .containsEntry("delivery_status", "PENDING")
                    .containsEntry("attempts", 0);
        }
    }

    /** A notification as a status change leaves it: pending, never attempted and due now. */
    private UUID pendingNotification(String channel) {
        return pendingNotification(channel, 1);
    }

    /** The same, due {@code secondsAgo} seconds ago: the worker takes the longest waiting first. */
    private UUID pendingNotification(String channel, int secondsAgo) {
        Instant due = clock.instant().minusSeconds(secondsAgo);
        UUID change =
                jdbc.queryForObject(
                        "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                                + " new_status, visible_to_citizen)"
                                + " VALUES (?, ?, ?, 'REPORTED', 'VALIDATED', true) RETURNING id",
                        UUID.class,
                        incident,
                        citizen,
                        Timestamp.from(due));
        return jdbc.queryForObject(
                "INSERT INTO notification (incident_id, recipient_id, status_change_id, message,"
                        + " channel, delivery_status, attempts, next_attempt_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?) RETURNING id",
                UUID.class,
                incident,
                citizen,
                change,
                MESSAGE,
                channel,
                Timestamp.from(due),
                Timestamp.from(due));
    }

    private Map<String, Object> notification(UUID id) {
        return jdbc.queryForMap(
                "SELECT delivery_status, attempts, sent_at FROM notification WHERE id = ?", id);
    }
}
