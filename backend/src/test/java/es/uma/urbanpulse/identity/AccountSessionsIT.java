package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.RefreshTokenCleanup;
import es.uma.urbanpulse.identity.service.SessionService;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Developer tests of the session rules that the acceptance tests of #61 do not reach: expiry,
 * revoking every session, reuse of a revoked refresh token and the clean-up of old ones (#125), a
 * token whose user is gone, and the shape of every error body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class})
class AccountSessionsIT {

    private static final String PASSWORD = "Correct-Horse-Battery-9";

    // The database keeps the SHA-256 of each refresh token in hex (RefreshTokens.hash).
    private static final String WHERE_TOKEN =
            " WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private SessionService sessions;
    @Autowired private RefreshTokenCleanup cleanup;
    @Autowired private TransactionTemplate transactions;

    private String email;

    // Each test signs up from its own client address: sign-up allows 10 attempts per address and
    // hour, fewer than the tests of this class.
    @BeforeEach
    void freshCitizen() throws Exception {
        var id = UUID.randomUUID();
        email = "dev-" + id + "@example.test";
        var clientAddress =
                "10.125." + (id.hashCode() & 0xff) + "." + ((id.hashCode() >> 8) & 0xff);
        mvc.perform(
                        post("/api/v1/registrations")
                                .with(
                                        request -> {
                                            request.setRemoteAddr(clientAddress);
                                            return request;
                                        })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": "Ana", "email": "%s", "password": "%s"}
                                        """
                                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void expiredAndUnknownRefreshTokensAreRejectedWithoutRevokingAnything() throws Exception {
        var phone = signIn();
        signIn();
        setExpiry(phone, "now() - interval '1 hour'");

        refresh(phone)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        refresh("not-a-refresh-token").andExpect(status().isUnauthorized());

        // Neither the expired token nor the other session was revoked.
        assertThat(activeSessions()).isEqualTo(2);
    }

    // #125: the server cannot tell whether the thief or the user sent the reused token, so both
    // lose their session.
    @Test
    void aReusedRefreshTokenRevokesEverySessionOfItsUser() throws Exception {
        var phone = signIn();
        var laptop = signIn();
        var rotated = read(refresh(phone).andExpect(status().isOk()), "$.refreshToken");

        refresh(phone)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(activeSessions()).isZero();
        refresh(rotated).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isUnauthorized());
    }

    // #125: a token revoked by sign-out counts as reused too.
    @Test
    void aRefreshTokenUsedAfterSignOutRevokesEverySessionOfItsUser() throws Exception {
        var phone = signInResponse();
        var laptop = signIn();
        mvc.perform(
                        delete("/api/v1/sessions/current")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + JsonPath.read(phone, "$.accessToken")))
                .andExpect(status().isNoContent());

        refresh(JsonPath.read(phone, "$.refreshToken")).andExpect(status().isUnauthorized());

        assertThat(activeSessions()).isZero();
        refresh(laptop).andExpect(status().isUnauthorized());
    }

    // #125: expiry is checked first, so a rotated token that has expired is just an expired one.
    @Test
    void aRotatedRefreshTokenThatHasExpiredRevokesNothing() throws Exception {
        var first = signIn();
        var second = read(refresh(first).andExpect(status().isOk()), "$.refreshToken");
        setExpiry(first, "now() - interval '1 hour'");

        refresh(first).andExpect(status().isUnauthorized());

        refresh(second).andExpect(status().isOk());
    }

    // #125: the client must not learn whether its token was reused, expired or never existed.
    @Test
    void theAnswerIsTheSameForAReusedAnExpiredAndAnUnknownToken() throws Exception {
        var reused = signIn();
        refresh(reused).andExpect(status().isOk());
        var expired = signIn();
        setExpiry(expired, "now() - interval '1 hour'");

        var reusedAnswer = answer(refresh(reused));

        assertThat(reusedAnswer)
                .isEqualTo(answer(refresh(expired)))
                .isEqualTo(answer(refresh("not-a-refresh-token")));
    }

    // #125: a reuse also revokes the session that a refresh of another token of the same user is
    // opening at that moment. The side transaction plays that refresh: it holds the user row and
    // inserts the new session, and commits only once the reuse is waiting for it.
    @Test
    void aReuseAlsoRevokesTheSessionThatAConcurrentRefreshIsOpening() throws Exception {
        var spent = signIn();
        refresh(spent).andExpect(status().isOk());
        var userId = userId();
        var sessionInserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> concurrentRefresh =
                    threads.submit(
                            () ->
                                    transactions.executeWithoutResult(
                                            transaction -> {
                                                jdbc.queryForObject(
                                                        "SELECT id FROM users WHERE id = ? FOR UPDATE",
                                                        UUID.class,
                                                        userId);
                                                jdbc.update(
                                                        "INSERT INTO refresh_token (user_id, token_hash,"
                                                                + " expires_at) VALUES (?, ?, now() +"
                                                                + " interval '1 day')",
                                                        userId,
                                                        UUID.randomUUID().toString());
                                                sessionInserted.countDown();
                                                awaitUninterruptibly(commit);
                                            }));
            sessionInserted.await();
            Future<Integer> reuse =
                    threads.submit(() -> refresh(spent).andReturn().getResponse().getStatus());
            waitUntilAQueryWaitsForALock();
            commit.countDown();

            concurrentRefresh.get(10, TimeUnit.SECONDS);
            assertThat(reuse.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        }
        assertThat(activeSessions()).isZero();
    }

    // #125, with the default retention of 30 days. A revoked token is kept until it expires: if it
    // were deleted before, presenting it again would look like an unknown token, not a reused one.
    @Test
    void theCleanupDeletesOnlyTokensExpiredLongerAgoThanTheRetention() throws Exception {
        var expiredLongAgo = signIn();
        var expiredRecently = signIn();
        var revokedButNotExpired = signIn();
        var active = signIn();
        setExpiry(expiredLongAgo, "now() - interval '31 days'");
        setExpiry(expiredRecently, "now() - interval '29 days'");
        jdbc.update(
                "UPDATE refresh_token SET revoked_at = now() - interval '31 days'" + WHERE_TOKEN,
                revokedButNotExpired);

        cleanup.deleteExpiredTokens();

        assertThat(isStored(expiredLongAgo)).isFalse();
        assertThat(isStored(expiredRecently)).isTrue();
        assertThat(isStored(revokedButNotExpired)).isTrue();
        assertThat(isStored(active)).isTrue();
    }

    @Test
    void aDisabledAccountLosesEverySessionAtItsFirstRefresh() throws Exception {
        var phone = signIn();
        signIn();
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", userId());

        refresh(phone).andExpect(status().isUnauthorized());

        assertThat(activeSessions()).isZero();
    }

    @Test
    void revokeAllSessionsSignsTheUserOutOfEveryDevice() throws Exception {
        var phone = signIn();
        var laptop = signIn();

        sessions.revokeAllSessions(userId());

        assertThat(activeSessions()).isZero();
        refresh(phone).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isUnauthorized());
    }

    @Test
    void theTokenOfAUserThatNoLongerExistsGets401() throws Exception {
        mvc.perform(
                        get("/api/v1/users/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(UUID.randomUUID(), Role.CITIZEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    /** {@code type} is required by the contract's {@code Problem}; Spring 7 leaves it empty. */
    @Test
    void everyErrorBodyHasTypeAndInstance() throws Exception {
        mvc.perform(
                        post("/api/v1/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email": "%s", "password": "Wrong-Password-1"}
                                        """
                                                .formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.instance").value("/api/v1/sessions"));
        mvc.perform(
                        post("/api/v1/registrations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.type").value("about:blank"));
        mvc.perform(
                        get("/api/v1/nothing-here")
                                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(Role.CITIZEN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value("The resource does not exist."));
    }

    private String signIn() throws Exception {
        return JsonPath.read(signInResponse(), "$.refreshToken");
    }

    private String signInResponse() throws Exception {
        return mvc.perform(
                        post("/api/v1/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                {"email": "%s", "password": "%s"}
                                                """
                                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                post("/api/v1/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    /** Status and error body without {@code traceId}, which differs on every answer. */
    private static Map<String, Object> answer(ResultActions result) throws Exception {
        Map<String, Object> problem = new HashMap<>(JsonPath.read(body(result), "$"));
        problem.remove("traceId");
        problem.put("httpStatus", result.andReturn().getResponse().getStatus());
        return problem;
    }

    /** Gives up after 5 seconds, so a request that never waits makes the test fail, not hang. */
    private void waitUntilAQueryWaitsForALock() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()",
                                Integer.class)
                        == 0) {
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

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    /** {@code expiresAt} is SQL, relative to the database clock. */
    private void setExpiry(String refreshToken, String expiresAt) {
        jdbc.update(
                "UPDATE refresh_token SET expires_at = " + expiresAt + WHERE_TOKEN, refreshToken);
    }

    private boolean isStored(String refreshToken) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token" + WHERE_TOKEN,
                        Integer.class,
                        refreshToken)
                == 1;
    }

    private UUID userId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private int activeSessions() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                userId());
    }
}
