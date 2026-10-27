package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acceptance tests of #61 for the sign-up, sign-in, refresh and sign-out API of #35 (ADR-0004).
 *
 * <p>They are written before the feature (ATDD, CONTRIBUTING.md) and fail until it exists. The HTTP
 * behaviour comes from the contract ({@code docs/api/openapi.yml}: {@code createRegistration},
 * {@code createSession}, {@code refreshSession}, {@code deleteCurrentSession}, {@code
 * getCurrentUser}) and the error codes from {@code docs/api.md}. What the contract does not fix
 * follows the notes agreed in the pull request of #35:
 *
 * <ul>
 *   <li>There is no endpoint to disable a user until #40, so the test disables the row with SQL.
 *   <li>The stored password hash starts with {@code {bcrypt}}.
 *   <li>The rate limits are properties; the test lowers them so it does not depend on the defaults.
 *   <li>Protected operations are called with a token from sign-in or from {@link TestTokens}.
 * </ul>
 *
 * <p>Each test uses its own email and client address, so the rate limits of one test never affect
 * another and the order does not matter.
 */
@SpringBootTest(
        properties = {
            "urbanpulse.security.sign-in-attempts.max=" + AuthenticationApiIT.SIGN_IN_LIMIT,
            "urbanpulse.security.sign-in-attempts.window=PT15M",
            "urbanpulse.security.sign-up-attempts.max=" + AuthenticationApiIT.SIGN_UP_LIMIT,
            "urbanpulse.security.sign-up-attempts.window=PT1H"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class})
class AuthenticationApiIT {

    static final int SIGN_IN_LIMIT = 3;
    static final int SIGN_UP_LIMIT = 3;

    private static final String REGISTRATIONS = "/api/v1/registrations";
    private static final String SESSIONS = "/api/v1/sessions";
    private static final String REFRESH = "/api/v1/sessions/refresh";
    private static final String CURRENT_SESSION = "/api/v1/sessions/current";
    private static final String ME = "/api/v1/users/me";

    private static final String VALID_PASSWORD = "Correct-Horse-Battery-9";
    private static final long ACCESS_TOKEN_LIFETIME_SECONDS = 15 * 60;
    private static final long LIFETIME_TOLERANCE_SECONDS = 60;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private String email;
    private String clientAddress;

    @BeforeEach
    void useFreshCitizenAndClient() {
        String id = UUID.randomUUID().toString();
        email = "citizen-" + id + "@example.test";
        clientAddress = "10.61." + (id.hashCode() & 0xff) + "." + ((id.hashCode() >> 8) & 0xff);
    }

    // T1
    @Test
    void citizenSignsUpAndSignsInWithAShortLivedCitizenToken() throws Exception {
        signUp(email, VALID_PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CITIZEN"));

        ResultActions session = signIn(email, VALID_PASSWORD);
        session.andExpect(jsonPath("$.tokenType").value("Bearer"));
        String accessToken = read(session, "$.accessToken");

        String claims = jwtClaims(accessToken);
        assertThat((String) JsonPath.read(claims, "$.role")).isEqualTo("CITIZEN");
        long lifetime =
                ((Number) JsonPath.read(claims, "$.exp")).longValue()
                        - ((Number) JsonPath.read(claims, "$.iat")).longValue();
        assertThat(lifetime)
                .isBetween(
                        ACCESS_TOKEN_LIFETIME_SECONDS - LIFETIME_TOLERANCE_SECONDS,
                        ACCESS_TOKEN_LIFETIME_SECONDS + LIFETIME_TOLERANCE_SECONDS);
        mvc.perform(withClient(get(ME)).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CITIZEN"));
    }

    // T1: a role sent in the sign-up body is ignored.
    @Test
    void signUpIgnoresARoleInTheBody() throws Exception {
        String body =
                """
                {"name": "Test Citizen", "email": "%s", "password": "%s", "role": "ADMINISTRATOR"}
                """
                        .formatted(email, VALID_PASSWORD);
        mvc.perform(json(post(REGISTRATIONS), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CITIZEN"));

        assertThat(storedRole(email)).isEqualTo("CITIZEN");
    }

    // T1: a wrong password and an unknown email get the same answer, so sign-in does not reveal
    // which emails have an account.
    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());

        String wrongPassword =
                expectInvalidCredentials(postSignIn(email, "Wrong-Password-1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String unknownEmail =
                expectInvalidCredentials(postSignIn("nobody-" + email, VALID_PASSWORD))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat((String) JsonPath.read(unknownEmail, "$.detail"))
                .isEqualTo(JsonPath.read(wrongPassword, "$.detail"));
    }

    // T2
    @Test
    void refreshRotatesTheTokenAndRejectsAReusedOne() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());
        String firstRefreshToken = read(signIn(email, VALID_PASSWORD), "$.refreshToken");

        ResultActions rotated = refresh(firstRefreshToken).andExpect(status().isOk());
        String secondRefreshToken = read(rotated, "$.refreshToken");
        assertThat(read(rotated, "$.accessToken")).isNotBlank();
        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);

        expectInvalidRefreshToken(refresh(firstRefreshToken));
        refresh(secondRefreshToken).andExpect(status().isOk());
    }

    // T3
    @Test
    void signOutRevokesTheRefreshTokenOfThatSessionOnly() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());
        ResultActions phone = signIn(email, VALID_PASSWORD);
        ResultActions laptop = signIn(email, VALID_PASSWORD);
        String phoneAccessToken = read(phone, "$.accessToken");

        signOut(phoneAccessToken).andExpect(status().isNoContent());

        expectInvalidRefreshToken(refresh(read(phone, "$.refreshToken")));
        refresh(read(laptop, "$.refreshToken")).andExpect(status().isOk());
    }

    // T3: signing out twice is not an error (the operation is idempotent).
    @Test
    void signingOutAgainStillAnswersNoContent() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());
        String accessToken = read(signIn(email, VALID_PASSWORD), "$.accessToken");

        signOut(accessToken).andExpect(status().isNoContent());
        signOut(accessToken).andExpect(status().isNoContent());
    }

    // T3: there is no endpoint to disable users until #40, so the test disables the row.
    @Test
    void disablingTheAccountRevokesItsRefreshTokensAndBlocksSignIn() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());
        String refreshToken = read(signIn(email, VALID_PASSWORD), "$.refreshToken");

        jdbc.update("UPDATE users SET enabled = false WHERE email = ?", email);

        expectInvalidRefreshToken(refresh(refreshToken));
        postSignIn(email, VALID_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    // T4
    @Test
    void weakPasswordIsRejectedAndNothingIsStored() throws Exception {
        expectFieldError(signUp(email, "Short-1"), "password");

        assertThat(usersWithEmail(email)).isZero();
    }

    // T4: the limit is 72 bytes in UTF-8, not 72 characters ("ñ" takes 2 bytes).
    @Test
    void passwordOverSeventyTwoBytesIsRejected() throws Exception {
        String password = "ñ".repeat(40);
        assertThat(password).hasSizeLessThanOrEqualTo(72);

        expectFieldError(signUp(email, password), "password");

        assertThat(usersWithEmail(email)).isZero();
    }

    // T5
    @Test
    void duplicateEmailReturnsConflictWithAStableCode() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());

        expectEmailAlreadyExists(signUp(email, VALID_PASSWORD));

        assertThat(usersWithEmail(email)).isEqualTo(1);
    }

    // T5: emails are stored in lower case, so another case is the same account.
    @Test
    void emailInAnotherCaseIsTheSameAccount() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());

        expectEmailAlreadyExists(signUp(email.toUpperCase(), VALID_PASSWORD));
        signIn(email.toUpperCase(), VALID_PASSWORD);
    }

    // T6
    @Test
    void repeatedWrongPasswordsAreRateLimited() throws Exception {
        signUp(email, VALID_PASSWORD).andExpect(status().isCreated());
        for (int attempt = 0; attempt < SIGN_IN_LIMIT; attempt++) {
            expectInvalidCredentials(postSignIn(email, "Wrong-Password-" + attempt));
        }

        expectTooManyAttempts(postSignIn(email, "Wrong-Password-again"));
        // Once the limit is reached, even the right password waits: otherwise guessing goes on.
        expectTooManyAttempts(postSignIn(email, VALID_PASSWORD));
    }

    // T6: sign-up is limited too, because EMAIL_ALREADY_EXISTS reveals which emails exist.
    @Test
    void repeatedSignUpsFromOneClientAreRateLimited() throws Exception {
        for (int attempt = 0; attempt < SIGN_UP_LIMIT; attempt++) {
            signUp(attempt + "-" + email, VALID_PASSWORD).andExpect(status().isCreated());
        }

        expectTooManyAttempts(signUp("again-" + email, VALID_PASSWORD));
        assertThat(usersWithEmail("again-" + email)).isZero();
    }

    // T7
    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        mvc.perform(withClient(get(ME))).andExpect(status().isUnauthorized());
        mvc.perform(withClient(delete(CURRENT_SESSION))).andExpect(status().isUnauthorized());
        mvc.perform(withClient(get("/api/v1/incidents"))).andExpect(status().isUnauthorized());
    }

    // T7: a valid token of an existing user opens the protected operations.
    @Test
    void currentUserIsTheOwnerOfTheToken() throws Exception {
        UUID operatorId =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test Operator', ?, '{bcrypt}not-used', 'MUNICIPAL_OPERATOR')"
                                + " RETURNING id",
                        UUID.class,
                        email);

        mvc.perform(
                        withClient(get(ME))
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(operatorId, Role.MUNICIPAL_OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operatorId.toString()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("MUNICIPAL_OPERATOR"));
    }

    // T7: the public operations are reachable without a token. An empty body may be rejected,
    // but never as unauthenticated.
    @Test
    void signUpSignInRefreshHealthAndDocsArePublic() throws Exception {
        for (String path : new String[] {REGISTRATIONS, SESSIONS, REFRESH}) {
            mvc.perform(json(post(path), "{}"))
                    .andExpect(status().is(not(oneOf(401, 403))))
                    .andExpect(status().is(not(404)));
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/openapi.yml")).andExpect(status().isOk());
    }

    // T8
    @Test
    void onlyAPasswordHashIsStoredAndNoResponseExposesIt() throws Exception {
        String signUpBody =
                signUp(email, VALID_PASSWORD)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        ResultActions session = signIn(email, VALID_PASSWORD);
        String meBody =
                mvc.perform(
                                withClient(get(ME))
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(read(session, "$.accessToken"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String storedHash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(storedHash).isNotEqualTo(VALID_PASSWORD).startsWith("{bcrypt}");

        String sessionBody = session.andReturn().getResponse().getContentAsString();
        for (String body : new String[] {signUpBody, sessionBody, meBody}) {
            assertThat(body)
                    .doesNotContain(VALID_PASSWORD)
                    .doesNotContain(storedHash)
                    .doesNotContainIgnoringCase("passwordHash")
                    .doesNotContainIgnoringCase("password_hash");
        }
    }

    private ResultActions signUp(String userEmail, String password) throws Exception {
        String body =
                """
                {"name": "Test Citizen", "email": "%s", "password": "%s"}
                """
                        .formatted(userEmail, password);
        return mvc.perform(json(post(REGISTRATIONS), body));
    }

    private ResultActions postSignIn(String userEmail, String password) throws Exception {
        String body =
                """
                {"email": "%s", "password": "%s"}
                """
                        .formatted(userEmail, password);
        return mvc.perform(json(post(SESSIONS), body));
    }

    private ResultActions signIn(String userEmail, String password) throws Exception {
        return postSignIn(userEmail, password).andExpect(status().isCreated());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                json(post(REFRESH), "{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
    }

    private ResultActions signOut(String accessToken) throws Exception {
        return mvc.perform(
                withClient(delete(CURRENT_SESSION))
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static ResultActions expectInvalidCredentials(ResultActions result) throws Exception {
        return expectProblem(result, 401, "INVALID_CREDENTIALS");
    }

    private static void expectInvalidRefreshToken(ResultActions result) throws Exception {
        expectProblem(result, 401, "INVALID_REFRESH_TOKEN");
    }

    private static void expectFieldError(ResultActions result, String field) throws Exception {
        expectProblem(result, 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field == '%s')]".formatted(field)).exists());
    }

    private static void expectEmailAlreadyExists(ResultActions result) throws Exception {
        expectProblem(result, 409, "EMAIL_ALREADY_EXISTS")
                .andExpect(content().string(not(containsString("SQL"))));
    }

    private static void expectTooManyAttempts(ResultActions result) throws Exception {
        expectProblem(result, 429, "TOO_MANY_ATTEMPTS")
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return withClient(request).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder request) {
        return request.with(
                mockRequest -> {
                    mockRequest.setRemoteAddr(clientAddress);
                    return mockRequest;
                });
    }

    private int usersWithEmail(String userEmail) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE lower(email) = lower(?)",
                        Integer.class,
                        userEmail);
        return count == null ? 0 : count;
    }

    private String storedRole(String userEmail) {
        return jdbc.queryForObject(
                "SELECT role FROM users WHERE email = ?", String.class, userEmail);
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    // Reads the payload without checking the signature: the test only looks at the claims.
    private static String jwtClaims(String jwt) {
        String[] parts = jwt.split("\\.");
        assertThat(parts).as("JWT header.payload.signature").hasSize(3);
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }
}
