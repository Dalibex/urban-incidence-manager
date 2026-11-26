package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.notifications.service.PushReceiptWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * #373, criteria 5 and 6: a later run reads Expo's receipts of the accepted tickets and records
 * them on each device delivery, without changing the notification's status; {@code
 * DeviceNotRegistered} deletes the token, unless the device registered it again after the message.
 *
 * <p>Written from the issue and the plan agreed for it (section 4 and the lows of section 9), not
 * from the implementation. Expo is a fake on the loopback interface ({@link FakeExpo}); the
 * deliveries are inserted with SQL as the delivery job leaves them, and the application clock is
 * fixed, so the ages of 15 minutes and 24 hours do not depend on when the test runs.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, PushReceiptIT.FixedClock.class})
@DisplayName("PushReceiptWorker, reading Expo's receipts")
class PushReceiptIT {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final String EXPO_ACCESS_TOKEN = "test-expo-access-token";

    // Expo has a receipt about 15 minutes after the ticket, and keeps it for 24 hours.
    private static final Duration READY = Duration.ofMinutes(15);
    private static final Duration KEPT_BY_EXPO = Duration.ofHours(24);
    private static final Duration A_SECOND = Duration.ofSeconds(1);

    private static final FakeExpo EXPO = FakeExpo.start();

    @DynamicPropertySource
    static void pointToTheFakeExpo(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.notifications.push-enabled", () -> "true");
        registry.add("urbanpulse.notifications.expo-base-url", () -> EXPO.baseUrl().toString());
        registry.add("urbanpulse.notifications.expo-access-token", () -> EXPO_ACCESS_TOKEN);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PushReceiptWorker worker;

    private UUID citizen;
    private UUID incident;
    private UUID notification;

    @BeforeEach
    void createADeliveredPushNotification() {
        EXPO.reset();
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN')"
                                + " RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
        Instant reportedAt = NOW.minus(Duration.ofDays(2));
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'VALIDATED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(reportedAt),
                        Timestamp.from(reportedAt));
        UUID change =
                jdbc.queryForObject(
                        "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                                + " new_status, visible_to_citizen)"
                                + " VALUES (?, ?, ?, 'REPORTED', 'VALIDATED', true) RETURNING id",
                        UUID.class,
                        incident,
                        citizen,
                        Timestamp.from(reportedAt));
        notification =
                jdbc.queryForObject(
                        "INSERT INTO notification (incident_id, recipient_id, status_change_id,"
                                + " message, channel, delivery_status, attempts, next_attempt_at,"
                                + " created_at, sent_at) VALUES (?, ?, ?, 'Your incident is now"
                                + " validated.', 'PUSH', 'DELIVERED', 1, ?, ?, ?) RETURNING id",
                        UUID.class,
                        incident,
                        citizen,
                        change,
                        Timestamp.from(reportedAt),
                        Timestamp.from(reportedAt),
                        Timestamp.from(reportedAt));
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        TestHistories.delete(jdbc, "incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM push_token WHERE user_id = ?", citizen);
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    @AfterAll
    static void stopTheFakeExpo() {
        EXPO.close();
    }

    @Nested
    @DisplayName("Which tickets a run asks for")
    class WhichTicketsARunAsksFor {

        @Test
        @DisplayName("one getReceipts call with the tickets accepted more than 15 minutes ago")
        void asksOnceForTheTicketsOlderThanFifteenMinutes() {
            // Arrange
            accepted(
                    device(NOW.minus(Duration.ofDays(1))),
                    "ticket-ready",
                    NOW.minus(READY).minus(A_SECOND));
            accepted(
                    device(NOW.minus(Duration.ofDays(1))),
                    "ticket-older",
                    NOW.minus(Duration.ofHours(2)));
            accepted(
                    device(NOW.minus(Duration.ofDays(1))),
                    "ticket-too-young",
                    NOW.minus(READY).plus(A_SECOND));

            // Act
            worker.readReceipts();

            // Assert
            assertThat(EXPO.receiptCalls())
                    .singleElement()
                    .satisfies(
                            call ->
                                    assertThat(FakeExpo.ticketIdsOf(call))
                                            .containsExactlyInAnyOrder(
                                                    "ticket-ready", "ticket-older"));
        }
    }

    @Nested
    @DisplayName("What a receipt records")
    class WhatAReceiptRecords {

        @Test
        @DisplayName("an ok receipt ends the delivery DELIVERED")
        void anOkReceiptEndsTheDeliveryDelivered() {
            // Arrange
            long delivery =
                    accepted(device(NOW.minus(Duration.ofDays(1))), "ticket-1", anHourAgo());
            EXPO.receiptOk("ticket-1");

            // Act
            worker.readReceipts();

            // Assert
            assertThat(delivery(delivery))
                    .containsEntry("status", "DELIVERED")
                    .containsEntry("error", null);
        }

        @Test
        @DisplayName("an error receipt ends the delivery FAILED with Expo's error code")
        void anErrorReceiptEndsTheDeliveryFailedWithItsCode() {
            // Arrange
            long delivery =
                    accepted(device(NOW.minus(Duration.ofDays(1))), "ticket-1", anHourAgo());
            EXPO.receiptError("ticket-1", "MessageRateExceeded");

            // Act
            worker.readReceipts();

            // Assert
            assertThat(delivery(delivery))
                    .containsEntry("status", "FAILED")
                    .containsEntry("error", "MessageRateExceeded");
        }

        @Test
        @DisplayName("the notification keeps its status and its final instant")
        void theNotificationKeepsItsStatus() {
            // Arrange
            accepted(device(NOW.minus(Duration.ofDays(1))), "ticket-1", anHourAgo());
            EXPO.receiptError("ticket-1", "MessageRateExceeded");
            Map<String, Object> before = notificationRow();

            // Act
            worker.readReceipts();

            // Assert
            assertThat(notificationRow()).isEqualTo(before);
        }

        // The lows of section 9 of the plan: a device that registered its token again after the
        // message is alive, whatever the old receipt says.
        @ParameterizedTest(name = "registered {0} the message -> token kept: {1}")
        @CsvSource({"before, false", "after, true"})
        @DisplayName(
                "DeviceNotRegistered fails the delivery and deletes the token only if it was not"
                        + " registered again after the message")
        void deviceNotRegisteredDeletesTheTokenUnlessRegisteredAgain(
                String registered, boolean kept) {
            // Arrange
            Instant sentAt = anHourAgo();
            Instant lastRegisteredAt =
                    registered.equals("before")
                            ? sentAt.minus(Duration.ofDays(1))
                            : sentAt.plus(Duration.ofMinutes(10));
            String token = device(lastRegisteredAt);
            long delivery = accepted(token, "ticket-1", sentAt);
            EXPO.receiptError("ticket-1", "DeviceNotRegistered");

            // Act
            worker.readReceipts();

            // Assert
            assertThat(delivery(delivery))
                    .containsEntry("status", "FAILED")
                    .containsEntry("error", "DeviceNotRegistered");
            assertThat(isStored(token)).isEqualTo(kept);
        }
    }

    @Nested
    @DisplayName("A ticket with no receipt")
    class ATicketWithNoReceipt {

        @Test
        @DisplayName("stays ACCEPTED and unchecked for the next run")
        void staysAcceptedForTheNextRun() {
            // Arrange
            long delivery =
                    accepted(device(NOW.minus(Duration.ofDays(1))), "ticket-1", anHourAgo());

            // Act
            worker.readReceipts();

            // Assert
            assertThat(delivery(delivery))
                    .containsEntry("status", "ACCEPTED")
                    .containsEntry("receipt_checked_at", null);
        }

        @Test
        @DisplayName("older than 24 hours: marked as checked, still ACCEPTED")
        void olderThanADayIsMarkedCheckedAndStaysAccepted() {
            // Arrange
            long delivery =
                    accepted(
                            device(NOW.minus(Duration.ofDays(3))),
                            "ticket-1",
                            NOW.minus(KEPT_BY_EXPO).minus(Duration.ofMinutes(1)));

            // Act
            worker.readReceipts();

            // Assert
            Map<String, Object> row = delivery(delivery);
            assertThat(row).containsEntry("status", "ACCEPTED");
            assertThat(row.get("receipt_checked_at")).isNotNull();
        }
    }

    private Instant anHourAgo() {
        return NOW.minus(Duration.ofHours(1));
    }

    /** A device of the citizen, last registered at {@code lastRegisteredAt}; returns its token. */
    private String device(Instant lastRegisteredAt) {
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                citizen,
                token,
                Timestamp.from(lastRegisteredAt),
                Timestamp.from(lastRegisteredAt));
        return token;
    }

    /** A delivery of the notification to {@code token}, accepted by Expo with {@code ticket}. */
    private long accepted(String token, String ticket, Instant sentAt) {
        return jdbc.queryForObject(
                "INSERT INTO push_delivery (notification_id, token, ticket_id, status, sent_at)"
                        + " VALUES (?, ?, ?, 'ACCEPTED', ?) RETURNING id",
                Long.class,
                notification,
                token,
                ticket,
                Timestamp.from(sentAt));
    }

    private Map<String, Object> delivery(long id) {
        return jdbc.queryForMap(
                "SELECT status, error, receipt_checked_at FROM push_delivery WHERE id = ?", id);
    }

    private Map<String, Object> notificationRow() {
        return jdbc.queryForMap(
                "SELECT delivery_status, attempts, sent_at FROM notification WHERE id = ?",
                notification);
    }

    private boolean isStored(String token) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM push_token WHERE token = ?", Integer.class, token)
                == 1;
    }
}
