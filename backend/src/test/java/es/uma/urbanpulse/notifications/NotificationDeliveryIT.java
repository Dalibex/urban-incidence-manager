package es.uma.urbanpulse.notifications;

import static es.uma.urbanpulse.notifications.ProviderAnswer.droppedConnection;
import static es.uma.urbanpulse.notifications.ProviderAnswer.httpStatus;
import static es.uma.urbanpulse.notifications.ProviderAnswer.okWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.notifications.service.NotificationDeliveryWorker;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.urbancontext.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * #373 (T3 of #376 and criteria 3, 4 and 6): the delivery job sends the pending notifications after
 * the status change committed, by Expo push and Brevo email, with bounded retries, one push message
 * per device of the citizen and one delivery row per device.
 *
 * <p>Written from the issue, the plan agreed for it (sections 3, 9 F7 and F8) and ADR-0031, not
 * from the implementation: both providers are fakes on the loopback interface ({@link FakeExpo},
 * {@link FakeBrevo}), each run of the worker is started by the test ({@code deliverPending()}), as
 * the scheduled one is off in the tests, and the clock moves to each retry ({@link MutableClock}).
 * The pending rows are inserted with SQL as a status change leaves them, and the outcome is read
 * with SQL. The rows of each test are deleted after it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, NotificationDeliveryIT.Clocks.class})
@DisplayName("NotificationDeliveryWorker, sending the pending notifications")
class NotificationDeliveryIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String EXPO_ACCESS_TOKEN = "test-expo-access-token";
    private static final String BREVO_API_KEY = "test-brevo-api-key";
    private static final String MAIL_FROM = "notifications@urbanpulse.test";
    private static final String MESSAGE = "Your incident \"Broken streetlight\" is now validated.";
    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");

    // Short, so a slow answer passes it quickly.
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Duration SLOWER_THAN_THE_TIMEOUT = TIMEOUT.multipliedBy(3);
    // The retries of the plan of #373, section 3: +1 min after the first attempt, +5 min after the
    // second, FAILED after the third.
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final Duration SECOND_RETRY = Duration.ofMinutes(5);
    // Expo takes up to 100 messages per request.
    private static final int DEVICES_FILLING_MORE_THAN_HALF_A_REQUEST = 60;

    private static final FakeExpo EXPO = FakeExpo.start();
    private static final FakeBrevo BREVO = FakeBrevo.start();

    @DynamicPropertySource
    static void pointToTheFakeProviders(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.notifications.push-enabled", () -> "true");
        registry.add("urbanpulse.notifications.expo-base-url", () -> EXPO.baseUrl().toString());
        registry.add("urbanpulse.notifications.expo-access-token", () -> EXPO_ACCESS_TOKEN);
        registry.add("urbanpulse.notifications.email-enabled", () -> "true");
        registry.add("urbanpulse.notifications.brevo-base-url", () -> BREVO.baseUrl().toString());
        registry.add("urbanpulse.notifications.brevo-api-key", () -> BREVO_API_KEY);
        registry.add("urbanpulse.notifications.mail-from", () -> MAIL_FROM);
        registry.add("urbanpulse.notifications.timeout", TIMEOUT::toString);
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private NotificationDeliveryWorker worker;
    @Autowired private MutableClock clock;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdIncidents = new ArrayList<>();

    private UUID category;
    private UUID operator;
    private UUID citizen;
    private String citizenEmail;
    private UUID incident;

    @BeforeEach
    void startClean() {
        EXPO.reset();
        BREVO.reset();
        clock.reset();
        category =
                jdbc.queryForObject(
                        "INSERT INTO category (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Lighting " + UUID.randomUUID());
        operator = user(Role.MUNICIPAL_OPERATOR);
        citizen = user(Role.CITIZEN);
        citizenEmail =
                jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, citizen);
        incident = incidentOf(citizen);
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        clock.reset();
        for (UUID created : createdIncidents) {
            TestHistories.delete(jdbc, "incident_id = ?", created);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", created);
            jdbc.update("DELETE FROM incident WHERE id = ?", created);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", category);
        for (UUID user : createdUsers) {
            jdbc.update("DELETE FROM push_token WHERE user_id = ?", user);
            // The audit trail is append-only and references its actor: a user who acted in it
            // stays, with a random email, so it cannot clash with another test.
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                    user);
        }
    }

    @AfterAll
    static void stopTheFakeProviders() {
        EXPO.close();
        BREVO.close();
    }

    @Nested
    @DisplayName("A status change")
    class AStatusChange {

        // T3 of #376: the providers are down, and the change does not wait for them.
        @Test
        @DisplayName("answers 200 without calling Expo or Brevo, even with both down")
        void answersWithoutCallingTheProviders() throws Exception {
            // Arrange
            channels(citizen, true, true);
            device(citizen);
            EXPO.answerEverySend(httpStatus(503));
            BREVO.answerEveryCall(httpStatus(503));

            // Act
            ResultActions answer =
                    mvc.perform(
                            post(INCIDENTS + "/" + incident + "/validation")
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            tokens.bearer(operator, Role.MUNICIPAL_OPERATOR))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"version\": 0}"));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(EXPO.sends()).isEmpty();
            assertThat(BREVO.calls()).isEmpty();
            assertThat(notificationsOf(incident))
                    .extracting(row -> row.get("delivery_status"))
                    .containsExactly("PENDING", "PENDING");
        }
    }

    @Nested
    @DisplayName("When the provider fails for a while (no answer, timeout, 429, 5xx)")
    class WhenTheProviderFailsForAWhile {

        static Stream<Arguments> transientFailures() {
            return Stream.of(
                    Arguments.of("PUSH", "503", httpStatus(503)),
                    Arguments.of("PUSH", "429", httpStatus(429)),
                    Arguments.of("PUSH", "a timeout", okWith("{}").after(SLOWER_THAN_THE_TIMEOUT)),
                    Arguments.of("PUSH", "a network error", droppedConnection()),
                    Arguments.of("EMAIL", "503", httpStatus(503)),
                    Arguments.of("EMAIL", "500", httpStatus(500)),
                    Arguments.of("EMAIL", "429", httpStatus(429)),
                    Arguments.of("EMAIL", "a timeout", okWith("{}").after(SLOWER_THAN_THE_TIMEOUT)),
                    Arguments.of("EMAIL", "a network error", droppedConnection()));
        }

        @ParameterizedTest(name = "{0} with {1}")
        @MethodSource("transientFailures")
        @DisplayName("the first run counts one attempt and leaves the row pending for +1 min")
        void theFirstRunCountsOneAttemptAndRetriesInOneMinute(
                String channel, String failure, ProviderAnswer answer) {
            // Arrange
            UUID notification = pendingNotificationWithItsChannel(channel);
            failEveryCall(channel, answer);
            Instant before = now();

            // Act
            worker.deliverPending();

            // Assert
            Instant after = nowRoundedUp();
            Map<String, Object> row = notification(notification);
            assertThat(row)
                    .containsEntry("delivery_status", "PENDING")
                    .containsEntry("attempts", 1)
                    .containsEntry("sent_at", null);
            assertThat(instant(row.get("next_attempt_at")))
                    .isBetween(before.plus(FIRST_RETRY), after.plus(FIRST_RETRY));
        }

        @ParameterizedTest(name = "{0} with {1}")
        @MethodSource("transientFailures")
        @DisplayName("the third failed run ends the row FAILED, with its final instant")
        void theThirdFailedRunEndsTheRowFailed(
                String channel, String failure, ProviderAnswer answer) {
            // Arrange
            UUID notification = pendingNotificationWithItsChannel(channel);
            failEveryCall(channel, answer);
            twoRunsThenDueForTheThird();
            Instant before = now();

            // Act
            worker.deliverPending();

            // Assert
            Instant after = nowRoundedUp();
            Map<String, Object> row = notification(notification);
            assertThat(row).containsEntry("delivery_status", "FAILED").containsEntry("attempts", 3);
            assertThat(instant(row.get("sent_at"))).isBetween(before, after);
        }

        @Test
        @DisplayName("a row is not taken again before its next attempt")
        void aRowIsNotTakenAgainBeforeItsNextAttempt() {
            // Arrange
            device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            EXPO.answerEverySend(httpStatus(503));
            worker.deliverPending();
            clock.advance(FIRST_RETRY.minusSeconds(1));

            // Act
            worker.deliverPending();

            // Assert
            assertThat(EXPO.sends()).hasSize(1);
            assertThat(notification(notification)).containsEntry("attempts", 1);
        }

        @Test
        @DisplayName("the second attempt comes after 1 min and leaves the third due 5 min later")
        void theSecondAttemptLeavesTheThirdDueFiveMinutesLater() {
            // Arrange
            device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            EXPO.answerEverySend(httpStatus(503));
            worker.deliverPending();
            clock.advance(FIRST_RETRY);
            Instant before = now();

            // Act
            worker.deliverPending();

            // Assert
            Instant after = nowRoundedUp();
            Map<String, Object> row = notification(notification);
            assertThat(EXPO.sends()).hasSize(2);
            assertThat(row)
                    .containsEntry("delivery_status", "PENDING")
                    .containsEntry("attempts", 2);
            assertThat(instant(row.get("next_attempt_at")))
                    .isBetween(before.plus(SECOND_RETRY), after.plus(SECOND_RETRY));
        }
    }

    @Nested
    @DisplayName("When the provider refuses the request (4xx other than 429)")
    class WhenTheProviderRefuses {

        @ParameterizedTest(name = "{0} answers {1}")
        @CsvSource({"PUSH, 400", "EMAIL, 401", "EMAIL, 400"})
        @DisplayName("the row is FAILED at once, after one attempt")
        void theRowIsFailedAtOnce(String channel, int status) {
            // Arrange
            UUID notification = pendingNotificationWithItsChannel(channel);
            failEveryCall(channel, httpStatus(status));
            Instant before = now();

            // Act
            worker.deliverPending();

            // Assert
            Instant after = nowRoundedUp();
            Map<String, Object> row = notification(notification);
            assertThat(row).containsEntry("delivery_status", "FAILED").containsEntry("attempts", 1);
            assertThat(instant(row.get("sent_at"))).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("A push notification, sent to every device of the citizen")
    class APushToEveryDevice {

        @Test
        @DisplayName("one Expo request carries a message for each device, with the incident id")
        void oneRequestCarriesAMessageForEachDevice() {
            // Arrange
            String phone = device(citizen);
            String tablet = device(citizen);
            pendingNotification(incident, citizen, "PUSH");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(EXPO.sends()).hasSize(1);
            FakeExpo.Call send = EXPO.sends().getFirst();
            assertThat(FakeExpo.recipientsOf(send)).containsExactlyInAnyOrder(phone, tablet);
            assertThat(FakeExpo.incidentIdsOf(send)).containsOnly(incident.toString()).hasSize(2);
        }

        @Test
        @DisplayName("the Expo request carries the access token as a bearer token")
        void theRequestCarriesTheAccessToken() {
            // Arrange
            device(citizen);
            pendingNotification(incident, citizen, "PUSH");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(EXPO.sends())
                    .singleElement()
                    .extracting(FakeExpo.Call::authorization)
                    .isEqualTo("Bearer " + EXPO_ACCESS_TOKEN);
        }

        @Test
        @DisplayName(
                "each device gets an ACCEPTED delivery with its ticket, and the row is DELIVERED")
        void eachDeviceGetsAnAcceptedDelivery() {
            // Arrange
            String phone = device(citizen);
            String tablet = device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            Instant before = now();

            // Act
            worker.deliverPending();

            // Assert
            Instant after = nowRoundedUp();
            assertThat(deliveriesOf(notification))
                    .extracting(
                            row -> row.get("token"),
                            row -> row.get("ticket_id"),
                            row -> row.get("status"),
                            row -> row.get("error"))
                    .containsExactlyInAnyOrder(
                            tuple(phone, FakeExpo.ticketOf(phone), "ACCEPTED", null),
                            tuple(tablet, FakeExpo.ticketOf(tablet), "ACCEPTED", null));
            Map<String, Object> row = notification(notification);
            assertThat(row).containsEntry("delivery_status", "DELIVERED");
            assertThat(instant(row.get("sent_at"))).isBetween(before, after);
        }

        @Test
        @DisplayName(
                "one ok ticket and one DeviceNotRegistered: DELIVERED, that delivery FAILED and its"
                        + " token deleted")
        void aDeviceNotRegisteredTicketFailsItsDeliveryAndDeletesItsToken() {
            // Arrange
            String phone = device(citizen);
            String oldPhone = device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            EXPO.failTicketOf(oldPhone, "DeviceNotRegistered");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(notification(notification)).containsEntry("delivery_status", "DELIVERED");
            assertThat(deliveryTo(notification, oldPhone))
                    .containsEntry("status", "FAILED")
                    .containsEntry("error", "DeviceNotRegistered");
            assertThat(deliveryTo(notification, phone)).containsEntry("status", "ACCEPTED");
            assertThat(tokensOf(citizen)).containsExactly(phone);
        }

        @Test
        @DisplayName("every ticket in error: the row is FAILED, the deliveries carry the error")
        void everyTicketInErrorFailsTheRow() {
            // Arrange
            String phone = device(citizen);
            String tablet = device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            EXPO.failTicketOf(phone, "MessageTooBig");
            EXPO.failTicketOf(tablet, "MessageTooBig");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(notification(notification)).containsEntry("delivery_status", "FAILED");
            assertThat(deliveriesOf(notification))
                    .extracting(row -> row.get("status"), row -> row.get("error"))
                    .containsOnly(tuple("FAILED", "MessageTooBig"));
        }

        @Test
        @DisplayName("a citizen with no device left at send time: FAILED, and nothing is sent")
        void aCitizenWithNoDeviceLeftFails() {
            // Arrange: the device that made the row is gone before the run.
            UUID notification = pendingNotification(incident, citizen, "PUSH");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(notification(notification)).containsEntry("delivery_status", "FAILED");
            assertThat(deliveriesOf(notification)).isEmpty();
            assertThat(EXPO.sends()).isEmpty();
        }

        // F7 of the plan: a request holds whole notifications, up to 100 messages.
        @Test
        @DisplayName("the messages of one notification never split across two requests")
        void theMessagesOfOneNotificationNeverSplit() {
            // Arrange
            Set<String> first = devices(citizen, DEVICES_FILLING_MORE_THAN_HALF_A_REQUEST);
            pendingNotification(incident, citizen, "PUSH");
            UUID otherCitizen = user(Role.CITIZEN);
            Set<String> second = devices(otherCitizen, DEVICES_FILLING_MORE_THAN_HALF_A_REQUEST);
            pendingNotification(incidentOf(otherCitizen), otherCitizen, "PUSH");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(EXPO.sends())
                    .hasSize(2)
                    .extracting(send -> Set.copyOf(FakeExpo.recipientsOf(send)))
                    .containsExactlyInAnyOrder(first, second);
        }
    }

    // F8 of the plan: an answer the sender cannot read is a transient failure of the whole batch.
    @Nested
    @DisplayName("When Expo answers 200 with a body that cannot be read")
    class WhenExpoAnswersSomethingUnreadable {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"{not json", "{\"data\": []}"})
        @DisplayName("the run counts one attempt for the row, without throwing")
        void theRunCountsOneAttempt(String body) {
            // Arrange
            device(citizen);
            device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            EXPO.answerEverySend(okWith(body));

            // Act
            Throwable thrown = catchThrowable(worker::deliverPending);

            // Assert
            assertThat(thrown).isNull();
            assertThat(notification(notification))
                    .containsEntry("delivery_status", "PENDING")
                    .containsEntry("attempts", 1);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"{not json", "{\"data\": []}"})
        @DisplayName("after the third run the row is FAILED")
        void afterTheThirdRunTheRowIsFailed(String body) {
            // Arrange
            device(citizen);
            device(citizen);
            UUID notification = pendingNotification(incident, citizen, "PUSH");
            EXPO.answerEverySend(okWith(body));
            twoRunsThenDueForTheThird();

            // Act
            worker.deliverPending();

            // Assert
            assertThat(notification(notification))
                    .containsEntry("delivery_status", "FAILED")
                    .containsEntry("attempts", 3);
        }

        @Test
        @DisplayName("the queue is not stuck: the next run sends the row again and a newer one")
        void theQueueIsNotStuck() {
            // Arrange
            device(citizen);
            UUID unreadable = pendingNotification(incident, citizen, "PUSH");
            EXPO.answerNextSend(okWith("{not json"));
            worker.deliverPending();
            clock.advance(FIRST_RETRY);
            UUID newer = pendingNotification(incident, citizen, "PUSH");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(notification(unreadable)).containsEntry("delivery_status", "DELIVERED");
            assertThat(notification(newer)).containsEntry("delivery_status", "DELIVERED");
        }
    }

    @Nested
    @DisplayName("An email notification")
    class AnEmail {

        @Test
        @DisplayName("goes to the citizen's address, from MAIL_FROM, with the stored message")
        void goesToTheCitizenWithTheStoredMessage() {
            // Arrange
            pendingNotification(incident, citizen, "EMAIL");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(BREVO.calls()).hasSize(1);
            String body = BREVO.calls().getFirst().body();
            assertThat((String) JsonPath.read(body, "$.to[0].email")).isEqualTo(citizenEmail);
            assertThat((String) JsonPath.read(body, "$.sender.email")).isEqualTo(MAIL_FROM);
            assertThat((String) JsonPath.read(body, "$.textContent")).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the Brevo request carries the API key in its api-key header")
        void theRequestCarriesTheApiKey() {
            // Arrange
            pendingNotification(incident, citizen, "EMAIL");

            // Act
            worker.deliverPending();

            // Assert
            assertThat(BREVO.calls())
                    .singleElement()
                    .extracting(FakeBrevo.Call::apiKey)
                    .isEqualTo(BREVO_API_KEY);
        }

        @Test
        @DisplayName("Brevo's 201 ends the row DELIVERED, with its final instant")
        void createdEndsTheRowDelivered() {
            // Arrange
            UUID notification = pendingNotification(incident, citizen, "EMAIL");
            Instant before = now();

            // Act
            worker.deliverPending();

            // Assert
            Instant after = nowRoundedUp();
            Map<String, Object> row = notification(notification);
            assertThat(row).containsEntry("delivery_status", "DELIVERED");
            assertThat(instant(row.get("sent_at"))).isBetween(before, after);
        }
    }

    /** First attempt, +1 min, second attempt, +5 min: the row is due for its third attempt. */
    private void twoRunsThenDueForTheThird() {
        worker.deliverPending();
        clock.advance(FIRST_RETRY);
        worker.deliverPending();
        clock.advance(SECOND_RETRY);
    }

    /** A pending row of the citizen on {@code channel}; a push row gets a device to go to. */
    private UUID pendingNotificationWithItsChannel(String channel) {
        if (channel.equals("PUSH")) {
            device(citizen);
        }
        return pendingNotification(incident, citizen, channel);
    }

    private void failEveryCall(String channel, ProviderAnswer answer) {
        if (channel.equals("PUSH")) {
            EXPO.answerEverySend(answer);
        } else {
            BREVO.answerEveryCall(answer);
        }
    }

    /**
     * A notification as a status change leaves it: pending, never attempted and due now, for a new
     * entry of the incident's history.
     */
    private UUID pendingNotification(UUID ofIncident, UUID recipient, String channel) {
        Instant due = clock.instant().minusSeconds(1);
        UUID change =
                jdbc.queryForObject(
                        "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                                + " new_status, visible_to_citizen)"
                                + " VALUES (?, ?, ?, 'REPORTED', 'VALIDATED', true) RETURNING id",
                        UUID.class,
                        ofIncident,
                        operator,
                        Timestamp.from(due));
        return jdbc.queryForObject(
                "INSERT INTO notification (incident_id, recipient_id, status_change_id, message,"
                        + " channel, delivery_status, attempts, next_attempt_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?) RETURNING id",
                UUID.class,
                ofIncident,
                recipient,
                change,
                MESSAGE,
                channel,
                Timestamp.from(due),
                Timestamp.from(due));
    }

    /** A classified incident of {@code reporter}, reported, with the report in its history. */
    private UUID incidentOf(UUID reporter) {
        UUID created =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', 'REPORTED',"
                                + " 'HIGH', 'Dark crossing near a school', 'MANUAL', ?, ?) RETURNING id",
                        UUID.class,
                        reporter,
                        category,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(REPORTED_AT));
        createdIncidents.add(created);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                created);
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, visible_to_citizen) VALUES (?, ?, ?, NULL, 'REPORTED', true)",
                created,
                reporter,
                Timestamp.from(REPORTED_AT));
        return created;
    }

    private UUID user(Role role) {
        UUID user =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                        UUID.class,
                        "Test " + role,
                        role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                        role.name());
        createdUsers.add(user);
        return user;
    }

    private void channels(UUID user, boolean push, boolean email) {
        jdbc.update(
                "UPDATE users SET notify_by_push = ?, notify_by_email = ? WHERE id = ?",
                push,
                email,
                user);
    }

    /** Registers a new device of {@code user} and returns its token. */
    private String device(UUID user) {
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                user,
                token,
                Timestamp.from(REPORTED_AT),
                Timestamp.from(REPORTED_AT));
        return token;
    }

    private Set<String> devices(UUID user, int count) {
        Set<String> registered = new HashSet<>();
        IntStream.range(0, count).forEach(i -> registered.add(device(user)));
        return registered;
    }

    private Map<String, Object> notification(UUID id) {
        return jdbc.queryForMap(
                "SELECT delivery_status, attempts, next_attempt_at, sent_at FROM notification"
                        + " WHERE id = ?",
                id);
    }

    private List<Map<String, Object>> notificationsOf(UUID ofIncident) {
        return jdbc.queryForList(
                "SELECT channel, delivery_status FROM notification WHERE incident_id = ?"
                        + " ORDER BY channel",
                ofIncident);
    }

    private List<Map<String, Object>> deliveriesOf(UUID notification) {
        return jdbc.queryForList(
                "SELECT token, ticket_id, status, error, sent_at FROM push_delivery"
                        + " WHERE notification_id = ?",
                notification);
    }

    private Map<String, Object> deliveryTo(UUID notification, String token) {
        return jdbc.queryForMap(
                "SELECT ticket_id, status, error FROM push_delivery"
                        + " WHERE notification_id = ? AND token = ?",
                notification,
                token);
    }

    private List<String> tokensOf(UUID user) {
        return jdbc.queryForList(
                "SELECT token FROM push_token WHERE user_id = ?", String.class, user);
    }

    /** The clock's instant, in the precision PostgreSQL keeps. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The upper bound of a stored instant: Postgres rounds a {@code timestamptz} to the
     * microsecond, so a value taken just before the clock tick can be stored up to 1 microsecond
     * above the truncated one.
     */
    private Instant nowRoundedUp() {
        Instant exact = clock.instant();
        Instant truncated = exact.truncatedTo(ChronoUnit.MICROS);
        return exact.equals(truncated) ? truncated : truncated.plus(1, ChronoUnit.MICROS);
    }

    private static Instant instant(Object timestamp) {
        if (timestamp instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        return ((Timestamp) timestamp).toInstant();
    }
}
