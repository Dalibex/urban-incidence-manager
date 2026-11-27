package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.SessionService;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.urbancontext.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * #373: the Expo push token of the citizen's phone, {@code createPushToken} and {@code
 * createPushTokenDeletion} of the contract ({@code docs/api/openapi.yml}). Registering is
 * idempotent, moves a token held by another user, and checks in the database that the session of
 * the access token is still active (ADR-0031); deleting goes by value, whoever holds the token, and
 * does not check the session (plan of #373, sections 5 and 10, F2). Only citizens may use them.
 *
 * <p>Written from the issue, the contract and the plan, not from the implementation. Each citizen
 * signs in through the API, so its access token carries the id of a real session ({@code sid}). The
 * application clock is {@link MutableClock}, so a registration is stamped with the clock and not
 * with the database's {@code now()}. The push tokens of each test are deleted after it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    TestTokens.class,
    ApiContract.EveryResponse.class,
    PushTokenApiIT.Clocks.class
})
@DisplayName("Push tokens of the citizen's phone")
class PushTokenApiIT {

    private static final String PUSH_TOKENS = "/api/v1/push-tokens";
    private static final String PUSH_TOKEN_DELETIONS = "/api/v1/push-token-deletions";
    private static final String PASSWORD = "Correct-Horse-Battery-9";

    // PushTokenRequest: maxLength 255. "ExponentPushToken[" and "]" take 19 of them.
    private static final int MAX_LENGTH = 255;
    private static final int WRAPPER_LENGTH = "ExponentPushToken[]".length();

    // The database keeps the SHA-256 of each refresh token in hex (RefreshTokens.hash).
    private static final String WHERE_TOKEN =
            " WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')";

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
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionService sessions;
    @Autowired private MutableClock clock;

    private final List<UUID> createdUsers = new ArrayList<>();

    private Account citizen;
    private Session session;

    @BeforeEach
    void signInACitizen() throws Exception {
        clock.reset();
        citizen = account(Role.CITIZEN);
        session = signIn(citizen);
    }

    @AfterEach
    void deleteThePushTokensOfTheTest() {
        createdUsers.forEach(user -> jdbc.update("DELETE FROM push_token WHERE user_id = ?", user));
    }

    @Nested
    @DisplayName("Registering a token")
    class Registering {

        @Test
        @DisplayName("a citizen with an active session gets 204 and one row stamped by the clock")
        void aCitizenWithAnActiveSessionRegistersTheToken() throws Exception {
            // Arrange
            String token = deviceToken();
            Instant before = clock.instant();

            // Act
            ResultActions answer = register(session, token);

            // Assert
            Instant after = clock.instant();
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).containsExactly(citizen.id());
            assertThat(lastRegisteredAt(token)).isBetween(lowerBound(before), upperBound(after));
        }

        @Test
        @DisplayName("registering the same token again keeps one row and only renews its time")
        void registeringAgainOnlyRenewsTheTime() throws Exception {
            // Arrange
            String token = deviceToken();
            register(session, token).andExpect(status().isNoContent());
            Instant createdAt = createdAt(token);
            clock.advance(Duration.ofMinutes(5));
            Instant before = clock.instant();

            // Act
            ResultActions answer = register(session, token);

            // Assert
            Instant after = clock.instant();
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).containsExactly(citizen.id());
            assertThat(lastRegisteredAt(token)).isBetween(lowerBound(before), upperBound(after));
            assertThat(createdAt(token)).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("a token held by another citizen moves to the one who registers it")
        void aTokenHeldByAnotherCitizenMoves() throws Exception {
            // Arrange
            String token = deviceToken();
            register(session, token).andExpect(status().isNoContent());
            Account next = account(Role.CITIZEN);
            Session nextSession = signIn(next);

            // Act
            ResultActions answer = register(nextSession, token);

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).containsExactly(next.id());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("es.uma.urbanpulse.notifications.PushTokenApiIT#validTokens")
        @DisplayName("accepts both Expo formats, up to 255 characters")
        void acceptsBothExpoFormats(String token) throws Exception {
            // Act
            ResultActions answer = register(session, token);

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).containsExactly(citizen.id());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("es.uma.urbanpulse.notifications.PushTokenApiIT#invalidBodies")
        @DisplayName("a body without a valid Expo token is unprocessable and stores nothing")
        void anInvalidTokenIsUnprocessable(String body) throws Exception {
            // Act
            ResultActions answer = send(PUSH_TOKENS, bearer(session), body);

            // Assert
            answer.andExpect(status().isUnprocessableContent())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(tokensOf(citizen.id())).isZero();
        }
    }

    @Nested
    @DisplayName("Registering checks that the session is still active")
    class SessionCheck {

        @Test
        @DisplayName("a session signed out is 401 and stores nothing")
        void aSignedOutSessionIsUnauthorized() throws Exception {
            // Arrange
            String token = deviceToken();
            signOut(session);

            // Act
            ResultActions answer = register(session, token);

            // Assert
            answer.andExpect(status().isUnauthorized())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(holdersOf(token)).isEmpty();
        }

        @Test
        @DisplayName("a session whose refresh token expired is 401 and stores nothing")
        void anExpiredSessionIsUnauthorized() throws Exception {
            // Arrange
            String token = deviceToken();
            jdbc.update(
                    "UPDATE refresh_token SET expires_at = ?" + WHERE_TOKEN,
                    Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))),
                    session.refreshToken());

            // Act
            ResultActions answer = register(session, token);

            // Assert
            answer.andExpect(status().isUnauthorized())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(holdersOf(token)).isEmpty();
        }

        @Test
        @DisplayName(
                "a session ended with every other session of the user is 401 and stores nothing")
        void aSessionEndedWithEveryOtherIsUnauthorized() throws Exception {
            // Arrange
            String token = deviceToken();
            sessions.revokeAllSessions(citizen.id());

            // Act
            ResultActions answer = register(session, token);

            // Assert
            answer.andExpect(status().isUnauthorized())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(holdersOf(token)).isEmpty();
        }

        @Test
        @DisplayName("a session still active is 204 although another one of the user signed out")
        void anotherSessionSignedOutDoesNotCount() throws Exception {
            // Arrange
            String token = deviceToken();
            Session phone = signIn(citizen);
            signOut(session);

            // Act
            ResultActions answer = register(phone, token);

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).containsExactly(citizen.id());
        }
    }

    @Nested
    @DisplayName("Deleting a token")
    class Deleting {

        @Test
        @DisplayName("a token the citizen holds is deleted: 204")
        void aTokenOfTheCitizenIsDeleted() throws Exception {
            // Arrange
            String token = storedToken(citizen.id());

            // Act
            ResultActions answer = send(PUSH_TOKEN_DELETIONS, bearer(session), body(token));

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).isEmpty();
        }

        @Test
        @DisplayName("a token held by another user is deleted too, by its value: 204")
        void aTokenOfAnotherUserIsDeleted() throws Exception {
            // Arrange
            Account previous = account(Role.CITIZEN);
            String token = storedToken(previous.id());

            // Act
            ResultActions answer = send(PUSH_TOKEN_DELETIONS, bearer(session), body(token));

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).isEmpty();
        }

        @Test
        @DisplayName("an unknown token is 204 and deletes no other token")
        void anUnknownTokenIsNoContent() throws Exception {
            // Arrange
            String kept = storedToken(citizen.id());

            // Act
            ResultActions answer = send(PUSH_TOKEN_DELETIONS, bearer(session), body(deviceToken()));

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(kept)).containsExactly(citizen.id());
        }

        // Plan of #373, section 10, F2: the app deletes its token after signing out, with the
        // access token it read before, so the deletion must not check the session.
        @Test
        @DisplayName("an access token whose session was signed out still deletes: 204")
        void aSignedOutSessionStillDeletes() throws Exception {
            // Arrange
            String token = storedToken(citizen.id());
            signOut(session);

            // Act
            ResultActions answer = send(PUSH_TOKEN_DELETIONS, bearer(session), body(token));

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(holdersOf(token)).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("es.uma.urbanpulse.notifications.PushTokenApiIT#invalidBodies")
        @DisplayName("a body without a valid Expo token is unprocessable")
        void anInvalidTokenIsUnprocessable(String body) throws Exception {
            // Act
            ResultActions answer = send(PUSH_TOKEN_DELETIONS, bearer(session), body);

            // Assert
            answer.andExpect(status().isUnprocessableContent())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Nested
    @DisplayName("Who may use them")
    class WhoMayUseThem {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ADMINISTRATOR", "ANALYST"})
        @DisplayName("staff may not register a token: 403, and nothing is stored")
        void staffMayNotRegister(Role role) throws Exception {
            // Arrange
            UUID staff = account(role).id();
            String token = deviceToken();

            // Act
            ResultActions answer = send(PUSH_TOKENS, tokens.bearer(staff, role), body(token));

            // Assert
            answer.andExpect(status().isForbidden())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(holdersOf(token)).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ADMINISTRATOR", "ANALYST"})
        @DisplayName("staff may not delete a token: 403, and the token stays")
        void staffMayNotDelete(Role role) throws Exception {
            // Arrange
            UUID staff = account(role).id();
            String token = storedToken(citizen.id());

            // Act
            ResultActions answer =
                    send(PUSH_TOKEN_DELETIONS, tokens.bearer(staff, role), body(token));

            // Assert
            answer.andExpect(status().isForbidden())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(holdersOf(token)).containsExactly(citizen.id());
        }

        @Test
        @DisplayName("registering without an access token is 401")
        void registeringWithoutATokenIsUnauthorized() throws Exception {
            // Act
            ResultActions answer = send(PUSH_TOKENS, null, body(deviceToken()));

            // Assert
            answer.andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("deleting without an access token is 401, and the token stays")
        void deletingWithoutATokenIsUnauthorized() throws Exception {
            // Arrange
            String token = storedToken(citizen.id());

            // Act
            ResultActions answer = send(PUSH_TOKEN_DELETIONS, null, body(token));

            // Assert
            answer.andExpect(status().isUnauthorized());
            assertThat(holdersOf(token)).containsExactly(citizen.id());
        }
    }

    static Stream<Named<String>> validTokens() {
        return Stream.of(
                Named.of("ExponentPushToken[...]", "ExponentPushToken[" + randomId() + "]"),
                Named.of("ExpoPushToken[...]", "ExpoPushToken[" + randomId() + "]"),
                Named.of("255 characters", tokenOfLength(MAX_LENGTH)));
    }

    static Stream<Named<String>> invalidBodies() {
        return Stream.of(
                Named.of("no Expo prefix", body("not-an-expo-token")),
                Named.of("nothing between the brackets", body("ExponentPushToken[]")),
                Named.of("no closing bracket", body("ExponentPushToken[abc")),
                Named.of("a space inside", body("ExponentPushToken[ab cd]")),
                Named.of("empty", body("")),
                Named.of("256 characters", body(tokenOfLength(MAX_LENGTH + 1))),
                Named.of("null", "{\"token\": null}"),
                Named.of("missing", "{}"));
    }

    private static String tokenOfLength(int length) {
        return "ExponentPushToken[" + "a".repeat(length - WRAPPER_LENGTH) + "]";
    }

    private static String randomId() {
        return UUID.randomUUID().toString();
    }

    private static String deviceToken() {
        return "ExponentPushToken[" + randomId() + "]";
    }

    private static String body(String token) {
        return "{\"token\": \"%s\"}".formatted(token);
    }

    private static Instant lowerBound(Instant before) {
        return before.truncatedTo(ChronoUnit.MICROS);
    }

    // PostgreSQL keeps microseconds and rounds the rest.
    private static Instant upperBound(Instant after) {
        return after.plus(1, ChronoUnit.MICROS);
    }

    private ResultActions register(Session caller, String token) throws Exception {
        return send(PUSH_TOKENS, bearer(caller), body(token));
    }

    /** A JSON {@code POST}, with the {@code Authorization} header unless it is null. */
    private ResultActions send(String path, String authorization, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private void signOut(Session caller) throws Exception {
        mvc.perform(
                        delete("/api/v1/sessions/current")
                                .header(HttpHeaders.AUTHORIZATION, bearer(caller)))
                .andExpect(status().isNoContent());
    }

    private Session signIn(Account account) throws Exception {
        String answer =
                mvc.perform(
                                post("/api/v1/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\": \"%s\", \"password\": \"%s\"}"
                                                        .formatted(account.email(), PASSWORD)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new Session(
                JsonPath.read(answer, "$.accessToken"), JsonPath.read(answer, "$.refreshToken"));
    }

    private static String bearer(Session caller) {
        return "Bearer " + caller.accessToken();
    }

    private Account account(Role role) {
        String email =
                role.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + "@example.com";
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES (?, ?, ?, ?) RETURNING id",
                        UUID.class,
                        "Test " + role,
                        email,
                        passwordEncoder.encode(PASSWORD),
                        role.name());
        createdUsers.add(id);
        return new Account(id, email);
    }

    /** A token stored with SQL, as a registration leaves it. */
    private String storedToken(UUID user) {
        String token = deviceToken();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                user,
                token,
                now,
                now);
        return token;
    }

    private List<UUID> holdersOf(String token) {
        return jdbc.queryForList(
                "SELECT user_id FROM push_token WHERE token = ?", UUID.class, token);
    }

    private int tokensOf(UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM push_token WHERE user_id = ?", Integer.class, user);
    }

    private Instant lastRegisteredAt(String token) {
        return jdbc.queryForObject(
                        "SELECT last_registered_at FROM push_token WHERE token = ?",
                        Timestamp.class,
                        token)
                .toInstant();
    }

    private Instant createdAt(String token) {
        return jdbc.queryForObject(
                        "SELECT created_at FROM push_token WHERE token = ?", Timestamp.class, token)
                .toInstant();
    }

    private record Account(UUID id, String email) {}

    private record Session(String accessToken, String refreshToken) {}
}
