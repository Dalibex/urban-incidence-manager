package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.SessionService;
import es.uma.urbanpulse.shared.security.TestTokens;
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

/**
 * Developer tests of the session rules that the acceptance tests of #61 do not reach: expiry,
 * revoking every session, a token whose user is gone, and the shape of every error body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class})
class AccountSessionsIT {

    private static final String PASSWORD = "Correct-Horse-Battery-9";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private SessionService sessions;

    private String email;

    @BeforeEach
    void freshCitizen() throws Exception {
        email = "dev-" + UUID.randomUUID() + "@example.test";
        mvc.perform(
                        post("/api/v1/registrations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": "Ana", "email": "%s", "password": "%s"}
                                        """
                                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void anExpiredRefreshTokenIsRejected() throws Exception {
        var refreshToken = signIn();
        jdbc.update(
                "UPDATE refresh_token SET expires_at = now() - interval '1 second'"
                        + " WHERE user_id = ?",
                userId());

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
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
        var body =
                mvc.perform(
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
        return JsonPath.read(body, "$.refreshToken");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                post("/api/v1/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
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
