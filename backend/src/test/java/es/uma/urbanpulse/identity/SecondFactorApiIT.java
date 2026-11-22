package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.MfaProperties;
import es.uma.urbanpulse.identity.service.RefreshTokenCleanup;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acceptance tests of #77 (T4 to T8 and T10 to T14) for the second factor of the staff (#57,
 * ADR-0021): {@code createSession} answering {@code 202}, {@code createMfaEnrollment}, {@code
 * createMfaVerification} and {@code createUserMfaReset}. T9 is the contract, checked on every
 * response by {@link ApiContract}; T1 to T3 and T15 are manual.
 *
 * <p>Written from the issue, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}. T12 and T14 follow the contract: an enrolment of an enrolled account is {@code
 * 409 MFA_ALREADY_ENROLLED}, a code for an enrolment that was never started is {@code 401
 * INVALID_MFA_CODE}, and the reset without a token and the enrolment with an unknown challenge are
 * {@code 401}.
 *
 * <p>The test configuration turns the second factor off; this class asks it of the four staff
 * roles, as the default of {@code MFA_REQUIRED_ROLES} does, and keeps the default limits of the
 * contract (5 wrong codes per challenge, 10 per account and 15 minutes). The codes are computed
 * here, with the same library, from the Base32 secret that the enrolment returns.
 *
 * <p>The clock of the application is frozen in the middle of a 30-second step, so a code never
 * changes step during a test; a test moves it on purpose. Each test uses its own accounts, so the
 * per-account limits of one test never affect another and the order does not matter. Nothing is
 * rolled back: the verifications commit on purpose when they fail.
 */
@SpringBootTest(
        properties = {
            "urbanpulse.security.mfa.required-roles="
                    + "MUNICIPAL_OPERATOR,TECHNICIAN,ADMINISTRATOR,ANALYST",
            "urbanpulse.security.sign-in-attempts.max=1000"
        })
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    TestTokens.class,
    ApiContract.EveryResponse.class,
    SecondFactorApiIT.Clocks.class
})
class SecondFactorApiIT {

    private static final String SESSIONS = "/api/v1/sessions";
    private static final String REFRESH = "/api/v1/sessions/refresh";
    private static final String ENROLLMENTS = "/api/v1/mfa-enrollments";
    private static final String VERIFICATIONS = "/api/v1/mfa-verifications";
    private static final String USERS = "/api/v1/users";
    private static final String PASSWORD_CHANGES = "/api/v1/password-changes";

    private static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final Duration STEP = Duration.ofSeconds(30);
    private static final int WRONG_CODES_PER_CHALLENGE = 5;
    private static final int WRONG_CODES_PER_ACCOUNT = 10;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    // The database keeps the SHA-256 of each challenge token in hex, like a refresh token.
    private static final String WHERE_TOKEN =
            " WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')";

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        FrozenClock frozenClock() {
            return new FrozenClock();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MfaProperties mfaProperties;
    @Autowired private FrozenClock clock;
    @Autowired private RefreshTokenCleanup cleanup;

    @BeforeEach
    void freezeTheClockInTheMiddleOfAStep() {
        var step = Math.floorDiv(Instant.now().getEpochSecond(), STEP.toSeconds());
        clock.set(Instant.ofEpochSecond(step * STEP.toSeconds() + STEP.toSeconds() / 2));
    }

    // --- T4 and T8: which roles are asked for a code --------------------------------------------

    // T4 and the evaluation of ADR-0021: a role in MFA_REQUIRED_ROLES gets no token for the right
    // password, only a challenge.
    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ADMINISTRATOR", "ANALYST"})
    void aStaffRoleGetsAChallengeAndNoTokensForTheRightPassword(Role role) throws Exception {
        // Arrange
        var account = account(role);

        // Act
        var result = signIn(account.email());

        // Assert
        result.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeToken").isNotEmpty())
                .andExpect(jsonPath("$.challengeExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.enrollmentRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        assertThat(refreshTokens(account.id())).isZero();
    }

    // T8: a role outside MFA_REQUIRED_ROLES signs in with the password alone, as before.
    @Test
    void aCitizenSignsInWithThePasswordAlone() throws Exception {
        // Arrange
        var citizen = account(Role.CITIZEN);

        // Act
        var result = signIn(citizen.email());

        // Assert
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.challengeToken").doesNotExist());
        assertThat(challenges(citizen.id())).isZero();
    }

    // T4: an enrolled account is asked for a code, not for an enrolment.
    @Test
    void anEnrolledAccountGetsAChallengeThatAsksForACode() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);

        // Act
        var result = signIn(enrolled.email());

        // Assert
        result.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enrollmentRequired").value(false))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    // T4: a valid code opens the session.
    @Test
    void aValidCodeOpensTheSession() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var challenge = challenge(enrolled.email());
        var sessionsBefore = activeRefreshTokens(enrolled.id());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore + 1);
    }

    // A password that must be changed is said before any challenge is created (#165).
    @Test
    void aPasswordThatMustBeChangedIsSaidBeforeAnyChallenge() throws Exception {
        // Arrange
        var account = account(Role.ANALYST);
        jdbc.update("UPDATE users SET password_change_required = true WHERE id = ?", account.id());

        // Act
        var result = signIn(account.email());

        // Assert
        expectProblem(result, 403, "PASSWORD_CHANGE_REQUIRED");
        assertThat(challenges(account.id())).isZero();
    }

    // --- T5: wrong, outside the window, reused, and the limits ----------------------------------

    // T5: a wrong code is refused, gives no tokens, and leaves the challenge open for another try.
    @Test
    void aWrongCodeIsRefusedAndTheChallengeStaysOpen() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var challenge = challenge(enrolled.email());
        var sessionsBefore = activeRefreshTokens(enrolled.id());

        // Act
        var result = verify(challenge, wrongCode(enrolled.secret()));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE")
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore);
        assertThat(isSpent(challenge)).isFalse();
    }

    // T5: the window is one step on each side, so a phone clock a little off still works.
    @ParameterizedTest
    @ValueSource(longs = {-1, 1})
    void theCodeOfANeighbourStepIsAccepted(long stepOffset) throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = challenge(enrolled.email());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), stepOffset));

        // Assert
        result.andExpect(status().isCreated());
    }

    // T5: two steps away is outside the window; twenty steps back is an old, expired code.
    @ParameterizedTest
    @ValueSource(longs = {-20, -2, 2})
    void aCodeOutsideTheWindowIsRefused(long stepOffset) throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = challenge(enrolled.email());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), stepOffset));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE");
    }

    // T5: a code that already opened a session is refused in the same step.
    @Test
    void aUsedCodeIsRefused() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var code = codeAt(enrolled.secret(), 0);
        verify(challenge(enrolled.email()), code).andExpect(status().isCreated());
        var secondChallenge = challenge(enrolled.email());

        // Act
        var result = verify(secondChallenge, code);

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE");
    }

    // T5: after the code of the next step was used, the current one is older, so it is refused too.
    @Test
    void anOlderCodeIsRefusedAfterANewerOneWasUsed() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        verify(challenge(enrolled.email()), codeAt(enrolled.secret(), 1))
                .andExpect(status().isCreated());
        var secondChallenge = challenge(enrolled.email());

        // Act
        var result = verify(secondChallenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE");
    }

    // T5: four wrong codes leave the challenge open, so the fifth try can still be right.
    @Test
    void aRightCodeAfterFourWrongOnesStillWorks() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var challenge = challenge(enrolled.email());
        sendWrongCodes(challenge, enrolled.secret(), WRONG_CODES_PER_CHALLENGE - 1);

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated());
    }

    // T5: the fifth wrong code spends the challenge, so even a right code goes back to the
    // password step.
    @Test
    void theFifthWrongCodeSpendsTheChallenge() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var challenge = challenge(enrolled.email());
        sendWrongCodes(challenge, enrolled.secret(), WRONG_CODES_PER_CHALLENGE);

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE")
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    // T5: nine wrong codes on the account, over two challenges, still leave one try.
    @Test
    void aRightCodeAfterNineWrongOnesOnTheAccountStillWorks() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ADMINISTRATOR);
        sendWrongCodes(challenge(enrolled.email()), enrolled.secret(), WRONG_CODES_PER_CHALLENGE);
        var challenge = challenge(enrolled.email());
        sendWrongCodes(
                challenge,
                enrolled.secret(),
                WRONG_CODES_PER_ACCOUNT - WRONG_CODES_PER_CHALLENGE - 1);

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated());
    }

    // T5: after ten wrong codes on the account, even a right code on a new challenge waits. The
    // right passwords in between do not give the attempts back.
    @Test
    void afterTenWrongCodesOnTheAccountEvenARightCodeIsRateLimited() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ADMINISTRATOR);
        for (var i = 0; i < WRONG_CODES_PER_ACCOUNT / WRONG_CODES_PER_CHALLENGE; i++) {
            sendWrongCodes(
                    challenge(enrolled.email()), enrolled.secret(), WRONG_CODES_PER_CHALLENGE);
        }
        var challenge = challenge(enrolled.email());
        var sessionsBefore = activeRefreshTokens(enrolled.id());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 429, "TOO_MANY_ATTEMPTS")
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore);
    }

    // --- T6: enrolment ---------------------------------------------------------------------------

    // T6: a pending enrolment does not protect the account yet: the next sign-in enrols again.
    @Test
    void aPendingEnrolmentDoesNotActivateTheSecondFactor() throws Exception {
        // Arrange
        var account = account(Role.TECHNICIAN);
        enroll(challenge(account.email())).andExpect(status().isCreated());

        // Act
        var result = signIn(account.email());

        // Assert
        result.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enrollmentRequired").value(true));
        assertThat(storedSecret(account.id())).isNull();
    }

    // T6: a wrong first code does not activate it either.
    @Test
    void aWrongFirstCodeDoesNotActivateTheSecondFactor() throws Exception {
        // Arrange
        var account = account(Role.TECHNICIAN);
        var challenge = challenge(account.email());
        var secret = secretOf(enroll(challenge).andExpect(status().isCreated()));

        // Act
        var result = verify(challenge, wrongCode(secret));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE");
        assertThat(storedSecret(account.id())).isNull();
        assertThat(auditActions(account.id())).isEmpty();
    }

    // T6: the first valid code activates the second factor, opens the session and records
    // ENROLL_MFA with the user as the actor and the target.
    @Test
    void theFirstValidCodeActivatesTheSecondFactorAndIsRecorded() throws Exception {
        // Arrange
        var account = account(Role.ANALYST);
        var challenge = challenge(account.email());
        var secret = secretOf(enroll(challenge).andExpect(status().isCreated()));

        // Act
        var result = verify(challenge, codeAt(secret, 0));

        // Assert
        result.andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        assertThat(storedSecret(account.id())).isNotNull();
        var entries =
                jdbc.queryForList(
                        "SELECT action, actor_id, target_type FROM audit_log WHERE target_id = ?",
                        account.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("action")).isEqualTo("ENROLL_MFA");
        assertThat(entries.getFirst().get("actor_id")).isEqualTo(account.id());
        assertThat(entries.getFirst().get("target_type")).isEqualTo("USER");
    }

    // T6: the enrolment returns the secret and an otpauth URI with it, for the QR code.
    @Test
    void theEnrolmentReturnsTheSecretAndItsOtpauthUri() throws Exception {
        // Arrange
        var account = account(Role.MUNICIPAL_OPERATOR);
        var challenge = challenge(account.email());

        // Act
        var result = enroll(challenge);

        // Assert
        result.andExpect(status().isCreated());
        var secret = secretOf(result);
        assertThat(base32Decode(secret)).hasSize(20);
        assertThat(read(result, "$.otpauthUri"))
                .startsWith("otpauth://totp/")
                .contains("secret=" + secret)
                .contains("digits=6")
                .contains("period=30");
    }

    // T6: the secret is stored encrypted: neither the Base32 the user saw nor its raw bytes.
    @Test
    void theSecretIsStoredEncrypted() throws Exception {
        // Arrange
        var account = account(Role.ANALYST);
        var challenge = challenge(account.email());
        var secret = secretOf(enroll(challenge).andExpect(status().isCreated()));

        // Act
        verify(challenge, codeAt(secret, 0)).andExpect(status().isCreated());

        // Assert
        var raw = base32Decode(secret);
        assertThat(storedSecret(account.id()))
                .isNotBlank()
                .doesNotContainIgnoringCase(secret)
                .doesNotContain(Base64.getEncoder().encodeToString(raw))
                .doesNotContainIgnoringCase(HexFormat.of().formatHex(raw));
    }

    // T6: once confirmed, no operation returns the secret again: a new enrolment is refused, and
    // neither the sign-in, the verification nor the user resources hold it.
    @Test
    void noOperationReturnsTheSecretAfterItIsConfirmed() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var administrator = account(Role.ADMINISTRATOR);
        var adminBearer = tokens.bearer(administrator.id(), Role.ADMINISTRATOR);
        var challenge = signIn(enrolled.email()).andExpect(status().isAccepted());
        var token = read(challenge, "$.challengeToken");

        // Act
        var bodies =
                List.of(
                        text(challenge),
                        text(enroll(token).andExpect(status().isConflict())),
                        text(
                                verify(token, codeAt(enrolled.secret(), 0))
                                        .andExpect(status().isCreated())),
                        text(
                                mvc.perform(
                                        get(USERS + "/me")
                                                .header(
                                                        HttpHeaders.AUTHORIZATION,
                                                        tokens.bearer(
                                                                enrolled.id(), Role.TECHNICIAN)))),
                        text(
                                mvc.perform(
                                        get(USERS + "/" + enrolled.id())
                                                .header(HttpHeaders.AUTHORIZATION, adminBearer))),
                        text(
                                mvc.perform(
                                        get(USERS)
                                                .param("email", enrolled.email())
                                                .header(HttpHeaders.AUTHORIZATION, adminBearer))));

        // Assert
        var stored = storedSecret(enrolled.id());
        for (var body : bodies) {
            assertThat(body)
                    .doesNotContainIgnoringCase(enrolled.secret())
                    .doesNotContain(stored)
                    .doesNotContain("otpauth")
                    .doesNotContainIgnoringCase("mfaSecret");
        }
    }

    // --- T7: reset by an administrator -----------------------------------------------------------

    // T7: the reset clears the secret and records RESET_MFA with the administrator and the reason.
    @Test
    void aResetIsAuditedWithTheReason() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var administrator = account(Role.ADMINISTRATOR);

        // Act
        var result = reset(enrolled.id(), "Lost phone, ticket 4711", administrator.id());

        // Assert
        result.andExpect(status().isNoContent());
        assertThat(storedSecret(enrolled.id())).isNull();
        var entries =
                jdbc.queryForList(
                        "SELECT actor_id, target_type, details::text AS details FROM audit_log"
                                + " WHERE target_id = ? AND action = 'RESET_MFA'",
                        enrolled.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("actor_id")).isEqualTo(administrator.id());
        assertThat(entries.getFirst().get("target_type")).isEqualTo("USER");
        assertThat((String) entries.getFirst().get("details"))
                .contains("\"reason\": \"Lost phone, ticket 4711\"");
    }

    // T7: the reset revokes every refresh token of the user.
    @Test
    void aResetRevokesEveryRefreshToken() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var administrator = account(Role.ADMINISTRATOR);

        // Act
        reset(enrolled.id(), "Lost phone", administrator.id()).andExpect(status().isNoContent());

        // Assert
        assertThat(activeRefreshTokens(enrolled.id())).isZero();
        expectProblem(refresh(enrolled.refreshToken()), 401, "INVALID_REFRESH_TOKEN");
    }

    // T7: a challenge opened before the reset no longer works, even with a right code.
    @Test
    void aResetSpendsTheOpenChallenges() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var administrator = account(Role.ADMINISTRATOR);
        var challenge = challenge(enrolled.email());

        // Act
        reset(enrolled.id(), "Lost phone", administrator.id()).andExpect(status().isNoContent());

        // Assert
        expectProblem(
                verify(challenge, codeAt(enrolled.secret(), 0)), 401, "INVALID_MFA_CHALLENGE");
    }

    // T7: a reset gives the account its wrong-code attempts back, so the first code of the new
    // phone is not rate limited by the codes tried before.
    @Test
    void aResetGivesTheAccountItsCodeAttemptsBack() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var administrator = account(Role.ADMINISTRATOR);
        for (var i = 0; i < WRONG_CODES_PER_ACCOUNT / WRONG_CODES_PER_CHALLENGE; i++) {
            sendWrongCodes(
                    challenge(enrolled.email()), enrolled.secret(), WRONG_CODES_PER_CHALLENGE);
        }
        expectProblem(
                verify(challenge(enrolled.email()), codeAt(enrolled.secret(), 0)),
                429,
                "TOO_MANY_ATTEMPTS");
        reset(enrolled.id(), "Lost phone", administrator.id()).andExpect(status().isNoContent());
        var challenge = challenge(enrolled.email());
        var newSecret = secretOf(enroll(challenge).andExpect(status().isCreated()));

        // Act
        var result = verify(challenge, codeAt(newSecret, 0));

        // Assert
        result.andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    // T7: administrators may reset their own second factor: their sessions end, and the entry
    // names them as the actor and the target.
    @Test
    void anAdministratorResetsTheirOwnSecondFactor() throws Exception {
        // Arrange
        var administrator = enrolled(Role.ADMINISTRATOR);

        // Act
        var result = reset(administrator.id(), "Lost phone", administrator.id());

        // Assert
        result.andExpect(status().isNoContent());
        assertThat(storedSecret(administrator.id())).isNull();
        assertThat(activeRefreshTokens(administrator.id())).isZero();
        var entries =
                jdbc.queryForList(
                        "SELECT actor_id FROM audit_log WHERE target_id = ? AND action = 'RESET_MFA'",
                        administrator.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("actor_id")).isEqualTo(administrator.id());
    }

    // The reason is validated like every stored text: missing, blank, over 500 characters or with
    // U+0000 is 422 on the field, and nothing changes.
    @ParameterizedTest
    @MethodSource("invalidResetBodies")
    void aResetWithAnInvalidReasonIsRefused(String body) throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var administrator = account(Role.ADMINISTRATOR);

        // Act
        var result =
                mvc.perform(
                        post(USERS + "/" + enrolled.id() + "/mfa-resets")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(administrator.id(), Role.ADMINISTRATOR)));

        // Assert
        expectFieldError(result, "reason");
        assertThat(storedSecret(enrolled.id())).isNotNull();
        assertThat(auditActions(enrolled.id())).doesNotContain("RESET_MFA");
    }

    static Stream<String> invalidResetBodies() {
        // The last one holds the JSON escape of U+0000, which the server reads as that character.
        return Stream.of(
                "{}",
                "{\"reason\":\"   \"}",
                "{\"reason\":\"%s\"}".formatted("a".repeat(501)),
                "{\"reason\":\"Lost\\u0000phone\"}");
    }

    // T7 (and the API side of T2): after a reset the user enrols again, and the codes of the old
    // secret no longer work.
    @Test
    void afterAResetTheOldSecretNoLongerWorks() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var administrator = account(Role.ADMINISTRATOR);
        reset(enrolled.id(), "Lost phone", administrator.id()).andExpect(status().isNoContent());
        var challenge = signIn(enrolled.email()).andExpect(status().isAccepted());
        challenge.andExpect(jsonPath("$.enrollmentRequired").value(true));
        var token = read(challenge, "$.challengeToken");
        enroll(token).andExpect(status().isCreated());

        // Act
        var result = verify(token, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE");
    }

    // T7: only an administrator resets a second factor.
    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ANALYST", "CITIZEN"})
    void anotherRoleCannotResetASecondFactor(Role role) throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var caller = account(role);

        // Act
        var result =
                mvc.perform(
                        resetRequest(enrolled.id(), "Lost phone")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(caller.id(), role)));

        // Assert
        result.andExpect(status().isForbidden());
        assertThat(storedSecret(enrolled.id())).isNotNull();
        assertThat(auditActions(enrolled.id())).doesNotContain("RESET_MFA");
    }

    // T7: a user without a second factor gets 204, and nothing changes nor is recorded.
    @Test
    void aResetOfAUserWithoutSecondFactorChangesNothing() throws Exception {
        // Arrange
        var citizen = account(Role.CITIZEN);
        signIn(citizen.email()).andExpect(status().isCreated());
        var administrator = account(Role.ADMINISTRATOR);

        // Act
        var result = reset(citizen.id(), "Just in case", administrator.id());

        // Assert
        result.andExpect(status().isNoContent());
        assertThat(auditActions(citizen.id())).isEmpty();
        assertThat(activeRefreshTokens(citizen.id())).isEqualTo(1);
    }

    // T13: a reset of an unknown user is 404.
    @Test
    void aResetOfAnUnknownUserIsNotFound() throws Exception {
        // Arrange
        var administrator = account(Role.ADMINISTRATOR);

        // Act
        var result = reset(UUID.randomUUID(), "Lost phone", administrator.id());

        // Assert
        result.andExpect(status().isNotFound());
    }

    // T14: the reset needs an access token.
    @Test
    void aResetWithoutATokenIsUnauthorized() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);

        // Act
        var result = mvc.perform(resetRequest(enrolled.id(), "Lost phone"));

        // Assert
        result.andExpect(status().isUnauthorized());
        assertThat(storedSecret(enrolled.id())).isNotNull();
    }

    // --- T10, T12 and T14: challenges that are not valid -----------------------------------------

    // T10: an unknown challenge gets no tokens.
    @Test
    void aVerificationWithAnUnknownChallengeIsUnauthorized() throws Exception {
        // Act
        var result = verify("unknown-" + UUID.randomUUID(), "123456");

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE")
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    // T14: an enrolment with an unknown challenge is 401.
    @Test
    void anEnrolmentWithAnUnknownChallengeIsUnauthorized() throws Exception {
        // Act
        var result = enroll("unknown-" + UUID.randomUUID());

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE")
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    // T10: a challenge expires when its lifetime ends, to the second.
    @Test
    void anExpiredChallengeIsRefusedEvenWithARightCode() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = challenge(enrolled.email());
        var sessionsBefore = activeRefreshTokens(enrolled.id());
        clock.advance(mfaProperties.challengeTtl());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE");
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore);
    }

    // T10: one second before it expires, the challenge still works.
    @Test
    void aChallengeStillWorksOneSecondBeforeItExpires() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = challenge(enrolled.email());
        clock.advance(mfaProperties.challengeTtl().minusSeconds(1));

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated());
    }

    // T10: a challenge that opened a session cannot open another one.
    @Test
    void aSpentChallengeIsRefused() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var challenge = challenge(enrolled.email());
        verify(challenge, codeAt(enrolled.secret(), 0)).andExpect(status().isCreated());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 1));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE");
    }

    // T12: an enrolment of an account that already has a second factor is a conflict.
    @Test
    void anEnrolmentOfAnEnrolledAccountIsAConflict() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var challenge = challenge(enrolled.email());

        // Act
        var result = enroll(challenge);

        // Assert
        expectProblem(result, 409, "MFA_ALREADY_ENROLLED");
        assertThat(text(result)).doesNotContain(enrolled.secret());
    }

    // T12: a code for an enrolment that was never started has no secret to match.
    @Test
    void aCodeForAnEnrolmentThatWasNeverStartedIsWrong() throws Exception {
        // Arrange
        var account = account(Role.TECHNICIAN);
        var challenge = challenge(account.email());

        // Act
        var result = verify(challenge, "123456");

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CODE");
        assertThat(storedSecret(account.id())).isNull();
    }

    // A code that is not six digits is 422 on the field, and does not count as a wrong code.
    @ParameterizedTest
    @ValueSource(strings = {"12345", "12a456"})
    void aCodeThatIsNotSixDigitsIsRefused(String code) throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var challenge = challenge(enrolled.email());

        // Act
        var result = verify(challenge, code);

        // Assert
        expectFieldError(result, "code");
        assertThat(failedAttempts(challenge)).isZero();
    }

    // A challenge token over 200 characters is 422 on the field, in the verification.
    @Test
    void aVerificationWithATooLongChallengeTokenIsRefused() throws Exception {
        // Act
        var result = verify("t".repeat(201), "123456");

        // Assert
        expectFieldError(result, "challengeToken");
    }

    // And in the enrolment.
    @Test
    void anEnrolmentWithATooLongChallengeTokenIsRefused() throws Exception {
        // Act
        var result = enroll("t".repeat(201));

        // Assert
        expectFieldError(result, "challengeToken");
    }

    // A password change spends the open challenges: one opened with the old password must not
    // finish a sign-in.
    @Test
    void aPasswordChangeSpendsTheOpenChallenges() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var challenge = challenge(enrolled.email());
        mvc.perform(
                        post(PASSWORD_CHANGES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"%s\",\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                                .formatted(
                                                        enrolled.email(),
                                                        PASSWORD,
                                                        "Another-Horse-Battery-7")))
                .andExpect(status().isNoContent());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE");
    }

    // The daily clean-up deletes the challenges that expired, and keeps the open ones.
    @Test
    void theDailyCleanupDeletesTheExpiredChallenges() throws Exception {
        // Arrange
        var account = account(Role.ANALYST);
        var expired = challenge(account.email());
        clock.advance(mfaProperties.challengeTtl().plusSeconds(1));
        var open = challenge(account.email());

        // Act
        cleanup.deleteExpiredTokens();

        // Assert
        assertThat(isStored(expired)).isFalse();
        assertThat(isStored(open)).isTrue();
    }

    // --- T11 and the account checked again between the steps -------------------------------------

    // T11: of several verifications at once of one challenge with a valid code, only one gets the
    // tokens.
    @Test
    void concurrentVerificationsOfOneChallengeOpenOnlyOneSession() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ADMINISTRATOR);
        var challenge = challenge(enrolled.email());
        var code = codeAt(enrolled.secret(), 0);
        var sessionsBefore = activeRefreshTokens(enrolled.id());
        int attempts = 5;
        var start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();

        // Act
        try (ExecutorService clients = Executors.newFixedThreadPool(attempts)) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                outcomes.add(
                        clients.submit(
                                () -> {
                                    start.await();
                                    var response =
                                            verify(challenge, code).andReturn().getResponse();
                                    return response.getStatus() == 201
                                            ? "201"
                                            : response.getStatus()
                                                    + " "
                                                    + JsonPath.read(
                                                            response.getContentAsString(),
                                                            "$.code");
                                }));
            }
            start.countDown();
        }

        // Assert
        var results = new ArrayList<String>();
        for (var outcome : outcomes) {
            results.add(outcome.get());
        }
        assertThat(results)
                .containsOnly("201", "401 INVALID_MFA_CHALLENGE")
                .containsOnlyOnce("201");
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore + 1);
    }

    // An account disabled after the password step gets no tokens, and its challenge is spent.
    @Test
    void anAccountDisabledBetweenTheStepsGetsNoTokens() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var challenge = challenge(enrolled.email());
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", enrolled.id());
        var sessionsBefore = activeRefreshTokens(enrolled.id());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 403, "ACCOUNT_DISABLED")
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore);
        assertThat(isSpent(challenge)).isTrue();
    }

    // An account whose role no longer needs the second factor goes back to the password step. The
    // properties are fixed for the whole context, so the role of the account changes instead of
    // the list: both reach the same check.
    @Test
    void anAccountWhoseRoleNoLongerNeedsTheSecondFactorGoesBackToThePasswordStep()
            throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = challenge(enrolled.email());
        jdbc.update("UPDATE users SET role = 'CITIZEN' WHERE id = ?", enrolled.id());
        var sessionsBefore = activeRefreshTokens(enrolled.id());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        expectProblem(result, 401, "INVALID_MFA_CHALLENGE")
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(sessionsBefore);
        assertThat(isSpent(challenge)).isTrue();
    }

    // --- refresh of a staff session and an enrolment that lost a race ----------------------------

    // A session opened with a code can be refreshed, and the token of that refresh too: the mark
    // of the second factor passes to each rotation.
    @Test
    void aSessionOpenedWithACodeKeepsRotating() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.MUNICIPAL_OPERATOR);
        var rotated =
                read(refresh(enrolled.refreshToken()).andExpect(status().isOk()), "$.refreshToken");

        // Act
        var result = refresh(rotated);

        // Assert
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    // A staff session without the second factor (one opened before #57) is refused and ends. It
    // is no sign of theft, so the session opened with a code (another tab) keeps working. Such a
    // session cannot be opened through the API in this context, so it is inserted.
    @Test
    void aStaffSessionWithoutTheSecondFactorCannotBeRefreshed() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var withoutCode = insertSessionWithoutSecondFactor(enrolled.id());

        // Act
        var result = refresh(withoutCode);

        // Assert
        expectProblem(result, 401, "INVALID_REFRESH_TOKEN")
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(activeRefreshTokens(enrolled.id())).isEqualTo(1);
        refresh(enrolled.refreshToken()).andExpect(status().isOk());
    }

    // Presenting that session again is refused once more, and is not taken for the reuse of a
    // stolen token, which would end the session opened with a code.
    @Test
    void aStaffSessionWithoutTheSecondFactorPresentedAgainLeavesTheOtherSessions()
            throws Exception {
        // Arrange
        var enrolled = enrolled(Role.TECHNICIAN);
        var withoutCode = insertSessionWithoutSecondFactor(enrolled.id());
        expectProblem(refresh(withoutCode), 401, "INVALID_REFRESH_TOKEN");

        // Act
        var result = refresh(withoutCode);

        // Assert
        expectProblem(result, 401, "INVALID_REFRESH_TOKEN");
        refresh(enrolled.refreshToken()).andExpect(status().isOk());
    }

    // A session opened when the role did not need the second factor is refused at the next
    // refresh once the role needs it. Only SQL changes a citizen into staff (the API refuses it),
    // and both reach the same check as a change of MFA_REQUIRED_ROLES.
    @Test
    void aSessionOpenedBeforeTheRoleNeededTheSecondFactorCannotBeRefreshed() throws Exception {
        // Arrange
        var account = account(Role.CITIZEN);
        var refreshToken =
                read(signIn(account.email()).andExpect(status().isCreated()), "$.refreshToken");
        jdbc.update("UPDATE users SET role = 'MUNICIPAL_OPERATOR' WHERE id = ?", account.id());

        // Act
        var result = refresh(refreshToken);

        // Assert
        expectProblem(result, 401, "INVALID_REFRESH_TOKEN");
        assertThat(activeRefreshTokens(account.id())).isZero();
    }

    // An enrolment challenge whose account confirmed a secret meanwhile (in another tab) is not an
    // enrolment any more. The confirmation spends the open challenges, so the one that lost the
    // race is set with SQL: a challenge of the enrolled account marked as an enrolment.
    @Test
    void anEnrolmentChallengeOfAnAccountEnrolledMeanwhileCannotEnrol() throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = staleEnrolmentChallenge(enrolled.email());

        // Act
        var result = enroll(challenge);

        // Assert
        expectProblem(result, 409, "MFA_ALREADY_ENROLLED");
    }

    // The same challenge accepts a right code of the confirmed secret, and records no second
    // ENROLL_MFA.
    @Test
    void anEnrolmentChallengeOfAnAccountEnrolledMeanwhileAcceptsTheConfirmedSecret()
            throws Exception {
        // Arrange
        var enrolled = enrolled(Role.ANALYST);
        var challenge = staleEnrolmentChallenge(enrolled.email());
        var storedBefore = storedSecret(enrolled.id());

        // Act
        var result = verify(challenge, codeAt(enrolled.secret(), 0));

        // Assert
        result.andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        assertThat(auditActions(enrolled.id())).containsExactly("ENROLL_MFA");
        assertThat(storedSecret(enrolled.id())).isEqualTo(storedBefore);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A challenge of an enrolled account, marked as an enrolment as if it predated the secret. */
    private String staleEnrolmentChallenge(String email) throws Exception {
        var challenge = challenge(email);
        jdbc.update("UPDATE mfa_challenges SET enrollment = true" + WHERE_TOKEN, challenge);
        return challenge;
    }

    /** A valid refresh token of the user, stored without the mark of the second factor. */
    private String insertSessionWithoutSecondFactor(UUID userId) {
        var plainToken = "no-code-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO refresh_token (user_id, token_hash, expires_at, second_factor)"
                        + " VALUES (?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?, false)",
                userId,
                plainToken,
                OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofDays(1)), ZoneOffset.UTC));
        return plainToken;
    }

    private record Account(UUID id, String email) {}

    private record Enrolled(UUID id, String email, String secret, String refreshToken) {}

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

    /**
     * A staff account that enrolled through the API, with the session the enrolment opened. The
     * clock then moves three steps, so every code of the new window, and the one two steps back, is
     * newer than the code of the enrolment: a test refuses a code only for the reason it names.
     */
    private Enrolled enrolled(Role role) throws Exception {
        var account = account(role);
        var challenge = challenge(account.email());
        var secret = secretOf(enroll(challenge).andExpect(status().isCreated()));
        var session = verify(challenge, codeAt(secret, 0)).andExpect(status().isCreated());
        clock.advance(STEP.multipliedBy(3));
        return new Enrolled(account.id(), account.email(), secret, read(session, "$.refreshToken"));
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

    private void sendWrongCodes(String challengeToken, String secret, int count) throws Exception {
        for (var i = 0; i < count; i++) {
            expectProblem(verify(challengeToken, wrongCode(secret)), 401, "INVALID_MFA_CODE");
        }
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                post(REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private ResultActions reset(UUID userId, String reason, UUID administratorId) throws Exception {
        return mvc.perform(
                resetRequest(userId, reason)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                tokens.bearer(administratorId, Role.ADMINISTRATOR)));
    }

    private static MockHttpServletRequestBuilder resetRequest(UUID userId, String reason) {
        return post(USERS + "/" + userId + "/mfa-resets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"%s\"}".formatted(reason));
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

    private static ResultActions expectFieldError(ResultActions result, String field)
            throws Exception {
        return expectProblem(result, 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field == '%s')]".formatted(field)).exists());
    }

    private static String text(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(text(result), path);
    }

    private String storedSecret(UUID userId) {
        return jdbc.queryForObject(
                "SELECT mfa_secret FROM users WHERE id = ?", String.class, userId);
    }

    private int refreshTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ?", Integer.class, userId);
    }

    private int activeRefreshTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                userId);
    }

    private int challenges(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM mfa_challenges WHERE user_id = ?", Integer.class, userId);
    }

    private boolean isSpent(String challengeToken) {
        return jdbc.queryForObject(
                "SELECT spent_at IS NOT NULL FROM mfa_challenges" + WHERE_TOKEN,
                Boolean.class,
                challengeToken);
    }

    private int failedAttempts(String challengeToken) {
        return jdbc.queryForObject(
                "SELECT failed_attempts FROM mfa_challenges" + WHERE_TOKEN,
                Integer.class,
                challengeToken);
    }

    private boolean isStored(String challengeToken) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM mfa_challenges" + WHERE_TOKEN,
                        Integer.class,
                        challengeToken)
                == 1;
    }

    private List<String> auditActions(UUID target) {
        return jdbc.queryForList(
                "SELECT action FROM audit_log WHERE target_id = ? ORDER BY instant, action",
                String.class,
                target);
    }

    /**
     * The time of the application, frozen so a code never changes step in the middle of a test; the
     * test sets it and moves it.
     */
    static final class FrozenClock extends Clock {

        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("The tests only need UTC");
        }
    }
}
