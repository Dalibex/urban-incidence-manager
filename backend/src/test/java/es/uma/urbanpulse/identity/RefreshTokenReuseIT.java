package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.service.RefreshTokenCleanup;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Acceptance tests of #140 for the reuse of a rotated refresh token (#125) that the developer tests
 * in {@link AccountSessionsIT} do not tell: the attacker who refreshes first (T2), a normal
 * rotation chain (T3) and a reuse after the clean-up ran (T9). They go through the API, as a client
 * would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefreshTokenReuseIT {

    private static final String PASSWORD = "Correct-Horse-Battery-9";

    // The database keeps the SHA-256 of each refresh token in hex (RefreshTokens.hash).
    private static final String WHERE_TOKEN =
            " WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefreshTokenCleanup cleanup;

    private String email;

    // Each test signs up from its own client address: sign-up allows 10 attempts per address and
    // hour.
    @BeforeEach
    void freshCitizen() throws Exception {
        var id = UUID.randomUUID();
        email = "reuse-" + id + "@example.com";
        var clientAddress =
                "10.140." + (id.hashCode() & 0xff) + "." + ((id.hashCode() >> 8) & 0xff);
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

    // T2: the thief refreshes first with the stolen token. When the user then presents the same
    // token, the server cannot tell who is who, so the thief's new session dies too.
    @Test
    void whenTheAttackerRefreshesFirstBothChainsDie() throws Exception {
        var stolen = signIn();
        var attackersToken = read(refresh(stolen).andExpect(status().isOk()), "$.refreshToken");

        refresh(stolen)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        refresh(attackersToken).andExpect(status().isUnauthorized());
        assertThat(activeSessions()).isZero();
    }

    // T3: a client that always sends its newest token is never treated as a reuse, and its other
    // sessions are untouched.
    @Test
    void aNormalRotationChainKeepsWorkingAndLeavesTheOtherSessionsAlone() throws Exception {
        var phone = signIn();
        var laptop = signIn();

        var second = read(refresh(phone).andExpect(status().isOk()), "$.refreshToken");
        var third = read(refresh(second).andExpect(status().isOk()), "$.refreshToken");
        read(refresh(third).andExpect(status().isOk()), "$.refreshToken");

        assertThat(isRevoked(phone)).isTrue();
        assertThat(isRevoked(second)).isTrue();
        assertThat(isRevoked(third)).isTrue();
        // The newest token of the phone and the laptop's one.
        assertThat(activeSessions()).isEqualTo(2);
        refresh(laptop).andExpect(status().isOk());
    }

    // T9: the clean-up keeps a rotated token while it would still work, so its reuse is still
    // detected afterwards.
    @Test
    void aReuseIsStillDetectedAfterTheCleanup() throws Exception {
        var rotated = signIn();
        var current = read(refresh(rotated).andExpect(status().isOk()), "$.refreshToken");

        cleanup.deleteExpiredTokens();

        assertThat(isStored(rotated)).isTrue();
        refresh(rotated).andExpect(status().isUnauthorized());
        refresh(current).andExpect(status().isUnauthorized());
        assertThat(activeSessions()).isZero();
    }

    private String signIn() throws Exception {
        return read(
                mvc.perform(
                                post("/api/v1/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"email": "%s", "password": "%s"}
                                                """
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isCreated()),
                "$.refreshToken");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(
                post("/api/v1/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private boolean isStored(String refreshToken) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token" + WHERE_TOKEN,
                        Integer.class,
                        refreshToken)
                == 1;
    }

    private boolean isRevoked(String refreshToken) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token"
                                + WHERE_TOKEN
                                + " AND revoked_at IS NOT NULL",
                        Integer.class,
                        refreshToken)
                == 1;
    }

    private int activeSessions() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token t JOIN users u ON u.id = t.user_id"
                        + " WHERE u.email = ? AND t.revoked_at IS NULL",
                Integer.class,
                email);
    }
}
