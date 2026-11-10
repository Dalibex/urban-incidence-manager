package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.service.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acceptance tests of #166 for the forced password change of #165: {@code createSession} refuses an
 * account that must change its password, and {@code createPasswordChange} replaces it with the
 * current one as the credential ({@code docs/api/openapi.yml}).
 *
 * <p>There is no endpoint to create staff accounts until #40, so each test inserts its own account
 * with SQL, with the flag an administrator's account will have. Each test also uses its own email
 * and client address, so the rate limit of one test never affects another and the order does not
 * matter.
 *
 * <p>T9 asks for a comparable time with an unknown email. The time is not measured here, because a
 * timing assertion would be flaky on a shared CI runner; the answer is checked to be the same, and
 * the time comes from {@code CredentialCheck}, which compares an unknown email against a hash too.
 */
@SpringBootTest(
        properties = {
            "urbanpulse.security.sign-in-attempts.max=" + PasswordChangeApiIT.ATTEMPT_LIMIT,
            "urbanpulse.security.sign-in-attempts.window=PT15M"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiContract.EveryResponse.class})
class PasswordChangeApiIT {

    static final int ATTEMPT_LIMIT = 3;

    private static final String SESSIONS = "/api/v1/sessions";
    private static final String REFRESH = "/api/v1/sessions/refresh";
    private static final String PASSWORD_CHANGES = "/api/v1/password-changes";

    private static final String GIVEN_PASSWORD = "Given-By-The-Admin-1";
    private static final String NEW_PASSWORD = "Only-I-Know-This-2";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoSpyBean private AuditService audit;

    private String email;
    private String clientAddress;

    @BeforeEach
    void useFreshEmailAndClient() {
        String id = UUID.randomUUID().toString();
        email = "staff-" + id + "@example.com";
        clientAddress = "10.66." + (id.hashCode() & 0xff) + "." + ((id.hashCode() >> 8) & 0xff);
    }

    // T2
    @Test
    void aFlaggedAccountGetsNoTokensWithTheRightPassword() throws Exception {
        UUID userId = staffAccount(true);

        expectProblem(postSignIn(email, GIVEN_PASSWORD), 403, "PASSWORD_CHANGE_REQUIRED")
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        assertThat(refreshTokensOf(userId)).isZero();
    }

    // T3
    @Test
    void theNewPasswordSignsInAndTheGivenOneNoLonger() throws Exception {
        staffAccount(true);

        changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        postSignIn(email, NEW_PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        expectInvalidCredentials(postSignIn(email, GIVEN_PASSWORD));
        assertThat(passwordChangeRequired()).isFalse();
    }

    // T3: the email is normalised as in sign-in, so the address the user typed in another case
    // is the same account.
    @Test
    void theEmailInAnotherCaseIsTheSameAccount() throws Exception {
        staffAccount(true);

        changePassword(email.toUpperCase(), GIVEN_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isNoContent());

        postSignIn(email, NEW_PASSWORD).andExpect(status().isCreated());
    }

    // T4: an account without the flag, as staff before #165, signs in as before.
    @Test
    void aStaffAccountWithoutTheFlagSignsInAsBefore() throws Exception {
        staffAccount(false);

        postSignIn(email, GIVEN_PASSWORD).andExpect(status().isCreated());
    }

    // T4: a citizen who signs up is never flagged. The dev and demo users are checked by
    // DevelopmentDatasetIT and DemoDatasetIT, which sign in with each of them.
    @Test
    void aCitizenWhoSignsUpSignsInDirectly() throws Exception {
        String body =
                """
                {"name": "Test Citizen", "email": "%s", "password": "%s"}
                """
                        .formatted(email, GIVEN_PASSWORD);
        mvc.perform(json(post("/api/v1/registrations"), body)).andExpect(status().isCreated());

        postSignIn(email, GIVEN_PASSWORD).andExpect(status().isCreated());
        assertThat(passwordChangeRequired()).isFalse();
    }

    // T5
    @Test
    void theChangeIsAuditedWithoutAnyPasswordOrHash() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();
        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);

        changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        List<Map<String, Object>> entries =
                jdbc.queryForList(
                        "SELECT actor_id, instant, action, target_type, details::text AS details"
                                + " FROM audit_log WHERE target_id = ?",
                        userId);
        assertThat(entries).hasSize(1);
        Map<String, Object> entry = entries.getFirst();
        // The account is its own actor: nobody else knows the password.
        assertThat(entry)
                .containsEntry("actor_id", userId)
                .containsEntry("action", "CHANGE_PASSWORD")
                .containsEntry("target_type", "USER");
        assertThat(((Timestamp) entry.get("instant")).toInstant()).isBetween(start, Instant.now());

        String details = (String) entry.get("details");
        assertThat((Boolean) JsonPath.read(details, "$.before.passwordChangeRequired")).isTrue();
        assertThat((Boolean) JsonPath.read(details, "$.after.passwordChangeRequired")).isFalse();
        assertThat(details)
                .doesNotContain(GIVEN_PASSWORD)
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain(givenHash)
                .doesNotContain(storedHash())
                .doesNotContainIgnoringCase("bcrypt")
                .doesNotContainIgnoringCase("hash");
    }

    // T7: a wrong password does not reveal that the account must change it.
    @Test
    void aWrongPasswordOnAFlaggedAccountGetsTheUsualAnswer() throws Exception {
        staffAccount(true);
        String flagged = expectInvalidCredentials(postSignIn(email, "Wrong-Password-1"));

        email = "unflagged-" + email;
        staffAccount(false);
        String unflagged = expectInvalidCredentials(postSignIn(email, "Wrong-Password-1"));

        for (String field : new String[] {"$.title", "$.detail"}) {
            assertThat((String) JsonPath.read(flagged, field))
                    .isEqualTo(JsonPath.read(unflagged, field));
        }
    }

    // T8
    @Test
    void aWrongCurrentPasswordChangesNothing() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();

        expectInvalidCredentials(changePassword(email, "Wrong-Password-1", NEW_PASSWORD));

        expectUnchanged(userId, givenHash);
    }

    // T9: an unknown email gets the same answer as a wrong password.
    @Test
    void anUnknownEmailGetsTheSameAnswerAsAWrongPassword() throws Exception {
        staffAccount(true);

        String wrongPassword =
                expectInvalidCredentials(changePassword(email, "Wrong-Password-1", NEW_PASSWORD));
        String unknownEmail =
                expectInvalidCredentials(
                        changePassword("nobody-" + email, GIVEN_PASSWORD, NEW_PASSWORD));

        assertThat((String) JsonPath.read(unknownEmail, "$.detail"))
                .isEqualTo(JsonPath.read(wrongPassword, "$.detail"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE email = ?",
                                Integer.class,
                                "nobody-" + email))
                .isZero();
    }

    // T10
    @Test
    void aNewPasswordTooShortIsUnprocessable() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();

        expectFieldError(changePassword(email, GIVEN_PASSWORD, "Short-1"), "newPassword");

        expectUnchanged(userId, givenHash);
    }

    // T10: the limit is 72 bytes in UTF-8, not 72 characters ("ñ" takes 2 bytes).
    @Test
    void aNewPasswordOverSeventyTwoBytesIsUnprocessable() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();
        String newPassword = "ñ".repeat(40);
        assertThat(newPassword).hasSizeLessThanOrEqualTo(72);

        expectFieldError(changePassword(email, GIVEN_PASSWORD, newPassword), "newPassword");

        expectUnchanged(userId, givenHash);
    }

    // T10: a current password over the limit is refused as invalid data, never a 500 from bcrypt.
    @Test
    void aCurrentPasswordOverSeventyTwoBytesIsUnprocessable() throws Exception {
        staffAccount(true);

        expectFieldError(changePassword(email, "ñ".repeat(40), NEW_PASSWORD), "currentPassword");
    }

    // T10
    @Test
    void theCurrentPasswordAsTheNewOneIsUnprocessable() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();

        expectFieldError(changePassword(email, GIVEN_PASSWORD, GIVEN_PASSWORD), "newPassword");

        expectUnchanged(userId, givenHash);
    }

    // T10: a request that cannot succeed does not spend an attempt of the rate limit.
    @Test
    void repeatingTheCurrentPasswordAsTheNewOneIsNeverRateLimited() throws Exception {
        staffAccount(true);

        for (int attempt = 0; attempt <= ATTEMPT_LIMIT; attempt++) {
            expectFieldError(changePassword(email, GIVEN_PASSWORD, GIVEN_PASSWORD), "newPassword");
        }

        changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    // T10: every blank field is reported at once.
    @Test
    void blankFieldsAreReportedTogether() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();

        expectProblem(
                        mvc.perform(
                                json(
                                        post(PASSWORD_CHANGES),
                                        "{\"email\": \" \", \"currentPassword\": \"\"}")),
                        422,
                        "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'currentPassword')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')]").exists());

        expectUnchanged(userId, givenHash);
    }

    // T11
    @Test
    void repeatedWrongCurrentPasswordsAreRateLimited() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();
        for (int attempt = 0; attempt < ATTEMPT_LIMIT; attempt++) {
            expectInvalidCredentials(
                    changePassword(email, "Wrong-Password-" + attempt, NEW_PASSWORD));
        }

        expectTooManyAttempts(changePassword(email, "Wrong-Password-again", NEW_PASSWORD));
        // Once the limit is reached, even the right password waits: otherwise guessing goes on.
        expectTooManyAttempts(changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD));
        expectUnchanged(userId, givenHash);
    }

    // T11: sign-in and the change share one limit; with one each a guesser would get twice the
    // attempts.
    @Test
    void wrongSignInsCountAgainstTheChangeToo() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();
        for (int attempt = 0; attempt < ATTEMPT_LIMIT; attempt++) {
            expectInvalidCredentials(postSignIn(email, "Wrong-Password-" + attempt));
        }

        expectTooManyAttempts(changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD));
        expectUnchanged(userId, givenHash);
    }

    // T12
    @Test
    void aDisabledAccountCannotChangeItsPassword() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", userId);

        expectProblem(changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD), 403, "ACCOUNT_DISABLED");

        expectUnchanged(userId, givenHash);
    }

    // T13: the change revokes the sessions opened with the old password.
    @Test
    void theChangeRevokesEveryOpenSession() throws Exception {
        staffAccount(false);
        String phone = read(postSignIn(email, GIVEN_PASSWORD), "$.refreshToken");
        String laptop = read(postSignIn(email, GIVEN_PASSWORD), "$.refreshToken");

        changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        expectProblem(refresh(phone), 401, "INVALID_REFRESH_TOKEN");
        expectProblem(refresh(laptop), 401, "INVALID_REFRESH_TOKEN");
    }

    // T14: the audit entry fails, so the whole change rolls back.
    @Test
    void aFailedAuditEntryLeavesThePasswordAndTheSessionsAsTheyWere() throws Exception {
        staffAccount(false);
        String givenHash = storedHash();
        String session = read(postSignIn(email, GIVEN_PASSWORD), "$.refreshToken");
        doThrow(new IllegalStateException("The audit log is not available"))
                .when(auditTarget())
                .record(any(), any(), any(), any(), any());

        // The 500 does not say what failed.
        changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("audit"))));

        assertThat(storedHash()).isEqualTo(givenHash);
        refresh(session).andExpect(status().isOk());
        postSignIn(email, GIVEN_PASSWORD).andExpect(status().isCreated());
        expectInvalidCredentials(postSignIn(email, NEW_PASSWORD));
    }

    // T14: on a flagged account, the obligation stays too.
    @Test
    void aFailedAuditEntryKeepsTheObligationToChange() throws Exception {
        UUID userId = staffAccount(true);
        String givenHash = storedHash();
        doThrow(new IllegalStateException("The audit log is not available"))
                .when(auditTarget())
                .record(any(), any(), any(), any(), any());

        changePassword(email, GIVEN_PASSWORD, NEW_PASSWORD).andExpect(status().is(500));

        expectUnchanged(userId, givenHash);
    }

    // T15: of two changes at once with the same current password, only one wins.
    @Test
    void ofTwoChangesAtOnceOnlyOneSucceeds() throws Exception {
        UUID userId = staffAccount(true);
        List<String> newPasswords = List.of("First-New-Password-1", "Second-New-Password-2");

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService clients = Executors.newFixedThreadPool(newPasswords.size())) {
            for (String newPassword : newPasswords) {
                statuses.add(
                        clients.submit(
                                () -> {
                                    start.await();
                                    return changePassword(email, GIVEN_PASSWORD, newPassword)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            start.countDown();
        }

        List<Integer> results = new ArrayList<>();
        for (Future<Integer> result : statuses) {
            results.add(result.get());
        }
        assertThat(results).containsExactlyInAnyOrder(204, 401);
        String winner = newPasswords.get(results.indexOf(204));
        String loser = newPasswords.get(results.indexOf(401));
        assertThat(passwordEncoder.matches(winner, storedHash())).isTrue();
        assertThat(passwordEncoder.matches(loser, storedHash())).isFalse();
        assertThat(changePasswordEntries(userId)).isEqualTo(1);
    }

    /**
     * The spy behind the transactional proxy. Stubbing through the proxy would call {@code record}
     * outside a transaction, which its {@code MANDATORY} propagation refuses.
     */
    private AuditService auditTarget() {
        return AopTestUtils.getTargetObject(audit);
    }

    /** Inserts an enabled staff account with {@link #GIVEN_PASSWORD}, as #40 will create it. */
    private UUID staffAccount(boolean passwordChangeRequired) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role, password_change_required)"
                        + " VALUES ('Test Operator', ?, ?, 'MUNICIPAL_OPERATOR', ?) RETURNING id",
                UUID.class,
                email,
                passwordEncoder.encode(GIVEN_PASSWORD),
                passwordChangeRequired);
    }

    private void expectUnchanged(UUID userId, String givenHash) {
        assertThat(storedHash()).isEqualTo(givenHash);
        assertThat(passwordChangeRequired()).isTrue();
        assertThat(changePasswordEntries(userId)).isZero();
    }

    private String storedHash() {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email);
    }

    private boolean passwordChangeRequired() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT password_change_required FROM users WHERE email = ?",
                        Boolean.class,
                        email));
    }

    private int changePasswordEntries(UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM audit_log"
                                + " WHERE target_id = ? AND action = 'CHANGE_PASSWORD'",
                        Integer.class,
                        userId);
        return count == null ? 0 : count;
    }

    private int refreshTokensOf(UUID userId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE user_id = ?",
                        Integer.class,
                        userId);
        return count == null ? 0 : count;
    }

    private ResultActions postSignIn(String userEmail, String password) throws Exception {
        String body =
                """
                {"email": "%s", "password": "%s"}
                """
                        .formatted(userEmail, password);
        return mvc.perform(json(post(SESSIONS), body));
    }

    private ResultActions changePassword(
            String userEmail, String currentPassword, String newPassword) throws Exception {
        String body =
                """
                {"email": "%s", "currentPassword": "%s", "newPassword": "%s"}
                """
                        .formatted(userEmail, currentPassword, newPassword);
        return mvc.perform(json(post(PASSWORD_CHANGES), body));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                json(post(REFRESH), "{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    /** Returns the body, so a test can compare two answers. */
    private static String expectInvalidCredentials(ResultActions result) throws Exception {
        return expectProblem(result, 401, "INVALID_CREDENTIALS")
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static void expectFieldError(ResultActions result, String field) throws Exception {
        expectProblem(result, 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field == '%s')]".formatted(field)).exists());
    }

    private static void expectTooManyAttempts(ResultActions result) throws Exception {
        expectProblem(result, 429, "TOO_MANY_ATTEMPTS")
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.with(
                        mockRequest -> {
                            mockRequest.setRemoteAddr(clientAddress);
                            return mockRequest;
                        })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(
                result.andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                path);
    }
}
