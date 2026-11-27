package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.uma.urbanpulse.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code notification} table after the migration of #373 (plan, section 1): a row belongs to
 * one status change, its channel is {@code EMAIL} or {@code PUSH} (ADR-0031: no {@code SMS}, no
 * {@code APP}), and a change has at most one row per recipient and channel, which is what makes
 * processing a change twice harmless.
 *
 * <p>Built only by Flyway, like {@code DatabaseMigrationIT}. Each test runs inside a transaction
 * that is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("The notification table")
class NotificationSchemaIT {

    private static final String INSERT_NOTIFICATION =
            "INSERT INTO notification (incident_id, recipient_id, status_change_id, message,"
                    + " channel, delivery_status, next_attempt_at, created_at)"
                    + " VALUES (?, ?, ?, 'Your incident is now validated.', ?, 'PENDING',"
                    + " now(), now())";

    @Autowired private JdbcTemplate jdbc;

    private UUID citizen;
    private UUID incident;
    private UUID change;

    @BeforeEach
    void createAStatusChange() {
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role) VALUES ('Ana', ?,"
                                + " '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Pothole', 'Deep pothole', 'VALIDATED',"
                                + " now(), now()) RETURNING id",
                        UUID.class,
                        citizen);
        change =
                jdbc.queryForObject(
                        "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                                + " new_status, visible_to_citizen)"
                                + " VALUES (?, ?, now(), 'REPORTED', 'VALIDATED', true) RETURNING id",
                        UUID.class,
                        incident,
                        citizen);
    }

    @Nested
    @DisplayName("Its channel")
    class ItsChannel {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"EMAIL", "PUSH"})
        @DisplayName("accepts EMAIL and PUSH, with no attempt yet")
        void acceptsEmailAndPush(String channel) {
            // Act
            jdbc.update(INSERT_NOTIFICATION, incident, citizen, change, channel);

            // Assert
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempts FROM notification WHERE status_change_id = ?"
                                            + " AND channel = ?",
                                    Integer.class,
                                    change,
                                    channel))
                    .isZero();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"SMS", "APP", "email"})
        @DisplayName("refuses any other channel, including the old SMS and APP")
        void refusesAnyOtherChannel(String channel) {
            // Act
            Throwable thrown =
                    catchThrowable(
                            () ->
                                    jdbc.update(
                                            INSERT_NOTIFICATION,
                                            incident,
                                            citizen,
                                            change,
                                            channel));

            // Assert
            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("Its keys")
    class ItsKeys {

        @Test
        @DisplayName("refuses a second row for the same change, recipient and channel")
        void refusesASecondRowForTheSameChangeRecipientAndChannel() {
            // Arrange
            jdbc.update(INSERT_NOTIFICATION, incident, citizen, change, "EMAIL");

            // Act
            Throwable thrown =
                    catchThrowable(
                            () ->
                                    jdbc.update(
                                            INSERT_NOTIFICATION,
                                            incident,
                                            citizen,
                                            change,
                                            "EMAIL"));

            // Assert
            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("accepts another channel for the same change and recipient")
        void acceptsAnotherChannelForTheSameChange() {
            // Arrange
            jdbc.update(INSERT_NOTIFICATION, incident, citizen, change, "EMAIL");

            // Act
            jdbc.update(INSERT_NOTIFICATION, incident, citizen, change, "PUSH");

            // Assert
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM notification WHERE status_change_id = ?",
                                    Integer.class,
                                    change))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("refuses a row without its status change")
        void refusesARowWithoutItsStatusChange() {
            // Act
            Throwable thrown =
                    catchThrowable(
                            () ->
                                    jdbc.update(
                                            INSERT_NOTIFICATION, incident, citizen, null, "EMAIL"));

            // Assert
            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("The channels of a user")
    class TheChannelsOfAUser {

        @Test
        @DisplayName("a user inserted without them has email on and push off")
        void aUserInsertedWithoutThemHasEmailOnAndPushOff() {
            // Act
            var channels =
                    jdbc.queryForMap(
                            "SELECT notify_by_push, notify_by_email FROM users WHERE id = ?",
                            citizen);

            // Assert
            assertThat(channels)
                    .containsEntry("notify_by_push", false)
                    .containsEntry("notify_by_email", true);
        }
    }
}
