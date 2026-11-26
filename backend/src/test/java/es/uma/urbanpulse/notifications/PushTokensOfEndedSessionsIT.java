package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #373 and ADR-0031: when every session of a user ends, every push token of that user is deleted in
 * the same transaction, so a phone no longer shows the notifications of an account that was taken
 * back. That happens wherever {@code RefreshTokenRepository.endAll} runs: a password change, an
 * account disabled, a second factor reset, a refresh on a disabled account and the reuse of a
 * revoked refresh token (plan of #373, sections 5 and 9, F5). A plain sign-out ends one session
 * only and leaves the tokens: the app deletes its own one afterwards.
 *
 * <p>Written from the plan and the contract, not from the implementation. Every operation goes
 * through the API; the push tokens are stored with SQL, as a registration leaves them, and read
 * with SQL. Each test also stores a token of another citizen, which must survive. The push tokens
 * of each test are deleted after it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@DisplayName("Push tokens when the sessions of their user end")
class PushTokensOfEndedSessionsIT {

    private static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final String NEW_PASSWORD = "Only-I-Know-This-2";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactions;

    private final List<UUID> createdUsers = new ArrayList<>();

    private Account citizen;
    private Account other;
    private String otherPhone;

    @BeforeEach
    void twoCitizensWithTheirPhones() {
        citizen = account(Role.CITIZEN);
        other = account(Role.CITIZEN);
        otherPhone = storedToken(other.id());
    }

    @AfterEach
    void deleteThePushTokensOfTheTest() {
        createdUsers.forEach(user -> jdbc.update("DELETE FROM push_token WHERE user_id = ?", user));
    }

    @Nested
    @DisplayName("Ending every session of the user deletes every token of that user")
    class EndingEverySession {

        @Test
        @DisplayName("a password change deletes the user's tokens and keeps the others")
        void aPasswordChangeDeletesTheTokens() throws Exception {
            // Arrange
            signIn(citizen);
            storedToken(citizen.id());
            storedToken(citizen.id());

            // Act
            ResultActions answer = changePassword(citizen);

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(tokensOf(citizen.id(), other.id())).containsExactly(otherPhone);
        }

        @Test
        @DisplayName("disabling the account deletes the user's tokens and keeps the others")
        void disablingTheAccountDeletesTheTokens() throws Exception {
            // Arrange
            signIn(citizen);
            storedToken(citizen.id());
            storedToken(citizen.id());
            UUID administrator = account(Role.ADMINISTRATOR).id();

            // Act
            ResultActions answer =
                    mvc.perform(
                            asAdministrator(
                                    administrator,
                                    json(
                                            patch("/api/v1/users/" + citizen.id()),
                                            "{\"enabled\": false}")));

            // Assert
            answer.andExpect(status().isOk());
            assertThat(tokensOf(citizen.id(), other.id())).containsExactly(otherPhone);
        }

        // resetMfa ends the sessions only when the user has a second factor, so the test gives the
        // citizen one with SQL: no citizen enrols through the API today.
        @Test
        @DisplayName("resetting the second factor deletes the user's tokens and keeps the others")
        void resettingTheSecondFactorDeletesTheTokens() throws Exception {
            // Arrange
            signIn(citizen);
            jdbc.update("UPDATE users SET mfa_secret = 'enrolled' WHERE id = ?", citizen.id());
            storedToken(citizen.id());
            storedToken(citizen.id());
            UUID administrator = account(Role.ADMINISTRATOR).id();

            // Act
            ResultActions answer =
                    mvc.perform(
                            asAdministrator(
                                    administrator,
                                    json(
                                            post("/api/v1/users/" + citizen.id() + "/mfa-resets"),
                                            "{\"reason\": \"Lost phone\"}")));

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(tokensOf(citizen.id(), other.id())).containsExactly(otherPhone);
        }

        @Test
        @DisplayName(
                "a refresh on a disabled account deletes the user's tokens and keeps the others")
        void aRefreshOnADisabledAccountDeletesTheTokens() throws Exception {
            // Arrange
            Session phone = signIn(citizen);
            storedToken(citizen.id());
            storedToken(citizen.id());
            jdbc.update("UPDATE users SET enabled = false WHERE id = ?", citizen.id());

            // Act
            ResultActions answer = refresh(phone.refreshToken());

            // Assert
            answer.andExpect(status().isUnauthorized());
            assertThat(tokensOf(citizen.id(), other.id())).containsExactly(otherPhone);
        }

        @Test
        @DisplayName(
                "reusing a revoked refresh token deletes the user's tokens and keeps the others")
        void aReuseDeletesTheTokens() throws Exception {
            // Arrange
            Session phone = signIn(citizen);
            refresh(phone.refreshToken()).andExpect(status().isOk());
            storedToken(citizen.id());
            storedToken(citizen.id());

            // Act
            ResultActions answer = refresh(phone.refreshToken());

            // Assert
            answer.andExpect(status().isUnauthorized());
            assertThat(tokensOf(citizen.id(), other.id())).containsExactly(otherPhone);
        }
    }

    @Nested
    @DisplayName("Signing out of one session")
    class SigningOut {

        @Test
        @DisplayName("keeps the user's tokens: the app deletes its own one")
        void aSignOutKeepsTheTokens() throws Exception {
            // Arrange
            Session phone = signIn(citizen);
            String citizenPhone = storedToken(citizen.id());

            // Act
            ResultActions answer =
                    mvc.perform(
                            delete("/api/v1/sessions/current")
                                    .header(HttpHeaders.AUTHORIZATION, bearer(phone)));

            // Assert
            answer.andExpect(status().isNoContent());
            assertThat(tokensOf(citizen.id(), other.id()))
                    .containsExactlyInAnyOrder(citizenPhone, otherPhone);
        }
    }

    @Nested
    @DisplayName("A registration and a password change at once")
    class Concurrently {

        /**
         * Plan of #373, section 9, F5: the registration locks the user, then reads its session
         * {@code FOR SHARE}, then stores the token; a password change locks the same user, ends
         * every session and then deletes the tokens. Whatever the order, the user must end with no
         * token.
         *
         * <p>The test pauses the real registration after its locks, with no production seam: a side
         * transaction stores the same token value for another citizen and does not commit, so the
         * registration's insert waits on the unique key of {@code push_token}. The password change
         * starts then and waits for the registration's locks. Once both wait, the side transaction
         * rolls back: the registration stores its token and commits, and the password change runs
         * after it. If the registration held no lock, the password change would finish first and
         * the token would survive it.
         */
        @Test
        @DisplayName("a registration holding its locks and a password change leave no token")
        void aRegistrationAndAPasswordChangeLeaveNoToken() throws Exception {
            // Arrange
            Session phone = signIn(citizen);
            String token = deviceToken();
            var tokenHeld = new CountDownLatch(1);
            var release = new CountDownLatch(1);

            try (ExecutorService threads = Executors.newFixedThreadPool(3)) {
                Future<?> sideTransaction =
                        threads.submit(
                                () ->
                                        transactions.executeWithoutResult(
                                                transaction -> {
                                                    insertToken(other.id(), token);
                                                    tokenHeld.countDown();
                                                    awaitUninterruptibly(release);
                                                    transaction.setRollbackOnly();
                                                }));
                tokenHeld.await(10, TimeUnit.SECONDS);
                Future<Integer> registration =
                        threads.submit(
                                () ->
                                        statusOf(
                                                mvc.perform(
                                                        json(
                                                                        post("/api/v1/push-tokens"),
                                                                        "{\"token\": \"%s\"}"
                                                                                .formatted(token))
                                                                .header(
                                                                        HttpHeaders.AUTHORIZATION,
                                                                        bearer(phone)))));
                waitUntilQueriesWaitForALock(1);
                Future<Integer> passwordChange =
                        threads.submit(() -> statusOf(changePassword(citizen)));
                waitUntilQueriesWaitForALock(2);

                // Act
                release.countDown();
                sideTransaction.get(10, TimeUnit.SECONDS);
                int registered = registration.get(10, TimeUnit.SECONDS);
                int changed = passwordChange.get(10, TimeUnit.SECONDS);

                // Assert
                assertThat(List.of(registered, changed)).containsExactly(204, 204);
                assertThat(tokensOf(citizen.id())).isEmpty();
            }
        }
    }

    private ResultActions changePassword(Account account) throws Exception {
        return mvc.perform(
                json(
                        post("/api/v1/password-changes"),
                        "{\"email\": \"%s\", \"currentPassword\": \"%s\", \"newPassword\": \"%s\"}"
                                .formatted(account.email(), PASSWORD, NEW_PASSWORD)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                json(
                        post("/api/v1/sessions/refresh"),
                        "{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
    }

    private Session signIn(Account account) throws Exception {
        String answer =
                mvc.perform(
                                json(
                                        post("/api/v1/sessions"),
                                        "{\"email\": \"%s\", \"password\": \"%s\"}"
                                                .formatted(account.email(), PASSWORD)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new Session(
                JsonPath.read(answer, "$.accessToken"), JsonPath.read(answer, "$.refreshToken"));
    }

    private MockHttpServletRequestBuilder asAdministrator(
            UUID administrator, MockHttpServletRequestBuilder request) {
        return request.header(
                HttpHeaders.AUTHORIZATION, tokens.bearer(administrator, Role.ADMINISTRATOR));
    }

    private static MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    private static int statusOf(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private static String deviceToken() {
        return "ExponentPushToken[" + UUID.randomUUID() + "]";
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
        insertToken(user, token);
        return token;
    }

    private void insertToken(UUID user, String token) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                user,
                token,
                now,
                now);
    }

    private List<String> tokensOf(UUID... users) {
        String placeholders = String.join(", ", Collections.nCopies(users.length, "?"));
        return jdbc.queryForList(
                "SELECT token FROM push_token WHERE user_id IN (" + placeholders + ")",
                String.class,
                (Object[]) users);
    }

    /** Gives up after 5 seconds, so a request that never waits makes the test fail, not hang. */
    private void waitUntilQueriesWaitForALock(int queries) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()",
                                Integer.class)
                        < queries) {
            Thread.sleep(20);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Account(UUID id, String email) {}

    private record Session(String accessToken, String refreshToken) {}
}
