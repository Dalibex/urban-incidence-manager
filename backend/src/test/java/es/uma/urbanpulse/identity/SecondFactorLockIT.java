package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * The total limit of consecutive wrong second-factor codes per account (#435, option A): the count
 * is kept in the database, only a valid code or an administrator's reset starts it again, and once
 * it reaches the limit the code step answers {@code 403 MFA_LOCKED} until a reset, and the lock is
 * audited as {@code LOCK_MFA}.
 *
 * <p>The limit is 7 here instead of 100, more than the 5 wrong codes that spend a challenge, so the
 * count has to cross challenges; the in-memory limit of 10 per 15 minutes is raised out of the way,
 * since it is tested by {@link SecondFactorApiIT}. The clock is frozen in the middle of a step, as
 * there.
 */
@SpringBootTest(
        properties = {
            "urbanpulse.security.mfa.required-roles="
                    + "MUNICIPAL_OPERATOR,TECHNICIAN,ADMINISTRATOR,ANALYST",
            "urbanpulse.security.mfa.max-consecutive-wrong-codes=" + SecondFactorLockIT.LIMIT,
            "urbanpulse.security.mfa.code-attempts.max=1000",
            "urbanpulse.security.sign-in-attempts.max=1000"
        })
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    TestTokens.class,
    ApiContract.EveryResponse.class,
    SecondFactorLockIT.Clocks.class
})
class SecondFactorLockIT {

    static final int LIMIT = 7;

    private static final String SESSIONS = "/api/v1/sessions";
    private static final String ENROLLMENTS = "/api/v1/mfa-enrollments";
    private static final String VERIFICATIONS = "/api/v1/mfa-verifications";
    private static final String USERS = "/api/v1/users";

    private static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final Duration STEP = Duration.ofSeconds(30);
    private static final int WRONG_CODES_PER_CHALLENGE = 5;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        SecondFactorApiIT.FrozenClock frozenClock() {
            return new SecondFactorApiIT.FrozenClock();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SecondFactorApiIT.FrozenClock clock;

    @BeforeEach
    void freezeTheClockInTheMiddleOfAStep() {
        var step = Math.floorDiv(Instant.now().getEpochSecond(), STEP.toSeconds());
        clock.set(Instant.ofEpochSecond(step * STEP.toSeconds() + STEP.toSeconds() / 2));
    }

    // One try short of the limit, over two challenges: a right code still opens the session.
    @Test
    void aRightCodeOneWrongCodeShortOfTheLimitStillWorks() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var challenge = sendWrongCodes(enrolled, LIMIT - 1);

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    // The wrong code that reaches the limit is still answered as a wrong code; from then on even a
    // right code on a new challenge, after a right password, is refused and opens no session.
    @Test
    void afterTheLimitEvenARightCodeOnANewChallengeIsRefused() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        sendWrongCodes(enrolled, LIMIT);
        var challenge = challenge(enrolled.email());
        var sessionsBefore = activeRefreshTokens(enrolled.id());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 403, "MFA_LOCKED");
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore);
    }

    // The lock is audited once, with the account as its own actor, when the limit is reached.
    @Test
    void reachingTheLimitIsAuditedOnce() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);

        // Act
        sendWrongCodes(enrolled, LIMIT);
        expectProblem(
                verify(challenge(enrolled.email()), codeAt(enrolled.secret(), 0)),
                403,
                "MFA_LOCKED");

        // Assert
        assertThat(auditActions(enrolled.id())).containsOnlyOnce("LOCK_MFA");
        assertThat(lockActor(enrolled.id())).isEqualTo(enrolled.id());
    }

    // The count is in the database, so a restart of the backend does not give the attempts back.
    @Test
    void theCountIsKeptInTheDatabase() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);

        // Act
        sendWrongCodes(enrolled, 3);

        // Assert
        assertThat(wrongCodes(enrolled.id())).isEqualTo(3);
    }

    // Only consecutive wrong codes count: a valid code of the real user starts again from zero.
    @Test
    void aValidCodeStartsTheCountAgain() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ADMINISTRATOR);
        var challenge = sendWrongCodes(enrolled, LIMIT - 1);
        verify(challenge, codeAt(enrolled.secret(), 0)).andExpect(status().isCreated());
        clock.advance(STEP.multipliedBy(3));
        challenge = sendWrongCodes(enrolled, LIMIT - 1);

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated());
        assertThat(wrongCodes(enrolled.id())).isZero();
    }

    // An administrator's reset unlocks the account: it enrols again and signs in.
    @Test
    void aResetUnlocksTheAccount() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var administrator = account(Role.ADMINISTRATOR);
        sendWrongCodes(enrolled, LIMIT);
        reset(enrolled.id(), administrator.id()).andExpect(status().isNoContent());
        var challenge = challenge(enrolled.email());
        var newSecret = secretOf(enroll(challenge).andExpect(status().isCreated()));

        // Act
        var result = verify(challenge, codeAt(newSecret, 0));

        // Assert
        result.andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        assertThat(wrongCodes(enrolled.id())).isZero();
    }

    // An account locked while it was still enrolling has no second factor to clear, but the reset
    // unlocks it all the same, and is audited.
    @Test
    void aResetUnlocksAnAccountLockedWhileEnrolling() throws Exception {
        // Arrange
        var enrolling = account(Role.MUNICIPAL_OPERATOR);
        var administrator = account(Role.ADMINISTRATOR);
        var sent = 0;
        while (sent < LIMIT) {
            var challenge = challenge(enrolling.email());
            for (var i = 0; i < WRONG_CODES_PER_CHALLENGE && sent < LIMIT; i++, sent++) {
                expectProblem(verify(challenge, "000000"), 401, "INVALID_MFA_CODE");
            }
        }
        expectProblem(verify(challenge(enrolling.email()), "000000"), 403, "MFA_LOCKED");

        // Act
        reset(enrolling.id(), administrator.id()).andExpect(status().isNoContent());

        // Assert
        var challenge = challenge(enrolling.email());
        var secret = secretOf(enroll(challenge).andExpect(status().isCreated()));
        verify(challenge, codeAt(secret, 0)).andExpect(status().isCreated());
        assertThat(auditActions(enrolling.id())).contains("LOCK_MFA", "RESET_MFA");
    }

    // Wrong codes sent at the same time on two challenges of the account all count.
    @Test
    void wrongCodesSentAtOnceAllCount() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var first = challenge(enrolled.email());
        var second = challenge(enrolled.email());
        var wrong = wrongCode(enrolled.secret());
        int perChallenge = 3;
        var start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();

        // Act
        try (ExecutorService clients = Executors.newFixedThreadPool(2 * perChallenge)) {
            for (var challenge : List.of(first, second)) {
                for (int i = 0; i < perChallenge; i++) {
                    outcomes.add(
                            clients.submit(
                                    () -> {
                                        start.await();
                                        return verify(challenge, wrong)
                                                .andReturn()
                                                .getResponse()
                                                .getStatus();
                                    }));
                }
            }
            start.countDown();
        }

        // Assert
        for (var outcome : outcomes) {
            assertThat(outcome.get()).isEqualTo(401);
        }
        assertThat(wrongCodes(enrolled.id())).isEqualTo(2 * perChallenge);
    }

    private record Account(UUID id, String email) {}

    private record Enrolled(UUID id, String email, String secret) {}

    /** An enabled account with {@link #PASSWORD}, which does not have to change it. */
    private Account account(Role role) {
        var email =
                role.name().toLowerCase(Locale.ROOT)
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 13)
                        + "@example.com";
        var id =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role, enabled)"
                                + " VALUES ('Test "
                                + role
                                + "', ?, ?, ?, true) RETURNING id",
                        UUID.class,
                        email,
                        passwordEncoder.encode(PASSWORD),
                        role.name());
        return new Account(id, email);
    }

    /** A staff account enrolled through the API; the clock then moves past its first code. */
    private Enrolled enrolled(Role role) throws Exception {
        var account = account(role);
        var challenge = challenge(account.email());
        var secret = secretOf(enroll(challenge).andExpect(status().isCreated()));
        verify(challenge, codeAt(secret, 0)).andExpect(status().isCreated());
        clock.advance(STEP.multipliedBy(3));
        return new Enrolled(account.id(), account.email(), secret);
    }

    /**
     * Sends {@code count} wrong codes, opening a new challenge whenever one is spent, and returns
     * the last challenge, still open unless the last code spent it.
     */
    private String sendWrongCodes(Enrolled enrolled, int count) throws Exception {
        var challenge = challenge(enrolled.email());
        for (var sent = 0; sent < count; sent++) {
            if (sent > 0 && sent % WRONG_CODES_PER_CHALLENGE == 0) {
                challenge = challenge(enrolled.email());
            }
            expectProblem(verify(challenge, wrongCode(enrolled.secret())), 401, "INVALID_MFA_CODE");
        }
        return challenge;
    }

    private ResultActions signIn(String email) throws Exception {
        return mvc.perform(
                post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                        .formatted(email, PASSWORD)));
    }

    private String challenge(String email) throws Exception {
        return read(signIn(email).andExpect(status().isAccepted()), "$.challengeToken");
    }

    private ResultActions enroll(String challengeToken) throws Exception {
        return mvc.perform(
                post(ENROLLMENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"%s\"}".formatted(challengeToken)));
    }

    private ResultActions verify(String challengeToken, String code) throws Exception {
        return mvc.perform(
                post(VERIFICATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"challengeToken\":\"%s\",\"code\":\"%s\"}"
                                        .formatted(challengeToken, code)));
    }

    private ResultActions reset(UUID userId, UUID administratorId) throws Exception {
        return mvc.perform(
                post(USERS + "/" + userId + "/mfa-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Locked out by wrong codes\"}")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                tokens.bearer(administratorId, Role.ADMINISTRATOR)));
    }

    /** The TOTP code of the step {@code stepOffset} steps away from the current one. */
    private String codeAt(String base32Secret, long stepOffset) throws Exception {
        var generator = new TimeBasedOneTimePasswordGenerator();
        var key = new SecretKeySpec(base32Decode(base32Secret), generator.getAlgorithm());
        return generator.generateOneTimePasswordString(
                key, clock.instant().plus(STEP.multipliedBy(stepOffset)), Locale.ROOT);
    }

    /** Six digits that are none of the codes the window accepts now. */
    private String wrongCode(String base32Secret) throws Exception {
        var accepted =
                Set.of(codeAt(base32Secret, -1), codeAt(base32Secret, 0), codeAt(base32Secret, 1));
        var candidate = 0;
        while (accepted.contains("%06d".formatted(candidate))) {
            candidate++;
        }
        return "%06d".formatted(candidate);
    }

    /** RFC 4648 Base32 without padding, the form of {@code MfaEnrollmentResponse.secret}. */
    private static byte[] base32Decode(String text) {
        var bytes = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (var c : text.toCharArray()) {
            var value = BASE32_ALPHABET.indexOf(c);
            assertThat(value).as("Base32 character %s", c).isNotNegative();
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bytes.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return bytes.toByteArray();
    }

    private static String secretOf(ResultActions enrolment) throws Exception {
        return read(enrolment, "$.secret");
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private int wrongCodes(UUID userId) {
        return jdbc.queryForObject(
                "SELECT mfa_failed_codes FROM users WHERE id = ?", Integer.class, userId);
    }

    private int activeRefreshTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                userId);
    }

    private List<String> auditActions(UUID target) {
        return jdbc.queryForList(
                "SELECT action FROM audit_log WHERE target_id = ? ORDER BY instant, action",
                String.class,
                target);
    }

    private UUID lockActor(UUID target) {
        return jdbc.queryForObject(
                "SELECT actor_id FROM audit_log WHERE target_id = ? AND action = 'LOCK_MFA'",
                UUID.class,
                target);
    }
}
