package es.uma.urbanpulse.shared.security;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The whole filter chain with real tokens: who gets {@code 401}, who gets {@code 403}, and what a
 * controller receives as the current user. The endpoints of {@link ProbeController} exist only in
 * this test. MockMvc does not run the error dispatch of a real server: what depends on it is in
 * {@link PublicOperationsIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    TestTokens.class,
    JwtAuthenticationIT.ProbeController.class
})
class JwtAuthenticationIT {

    private static final String ME = "/api/v1/test-probe/me";
    private static final String ADMIN_ONLY = "/api/v1/test-probe/admin";

    @Autowired private MockMvc mvc;
    @Autowired private TestTokens tokens;
    @Autowired private JwtEncoder encoder;

    @Test
    void withoutTokenAnswers401AsProblemDetails() throws Exception {
        mvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value(ME));
    }

    /** Each way a token can be wrong ends in 401 with Problem Details, never in 500. */
    enum BadToken {
        MALFORMED,
        UNSIGNED,
        SIGNED_WITH_ANOTHER_SECRET,
        EXPIRED,
        WITHOUT_EXPIRY,
        NOT_AN_ACCESS_TOKEN,
        UNKNOWN_ROLE,
        SUBJECT_NOT_A_UUID
    }

    @ParameterizedTest
    @EnumSource(BadToken.class)
    void rejectsAnyBadTokenWith401AsProblemDetails(BadToken bad) throws Exception {
        mvc.perform(get(ME).header(AUTHORIZATION, "Bearer " + tokenThatIs(bad)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void doesNotAcceptHttpBasicAnyMore() throws Exception {
        mvc.perform(get(ME).header(AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givesTheControllerTheUserOfTheToken() throws Exception {
        var userId = UUID.randomUUID();

        mvc.perform(get(ME).header(AUTHORIZATION, tokens.bearer(userId, Role.TECHNICIAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("TECHNICIAN"));
    }

    @Test
    void letsTheRequiredRoleThrough() throws Exception {
        mvc.perform(get(ADMIN_ONLY).header(AUTHORIZATION, tokens.bearer(Role.ADMINISTRATOR)))
                .andExpect(status().isOk());
    }

    @Test
    void answers403AsProblemDetailsToAnotherRole() throws Exception {
        mvc.perform(get(ADMIN_ONLY).header(AUTHORIZATION, tokens.bearer(Role.ANALYST)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value(ADMIN_ONLY));
    }

    @Test
    void keepsTheRestOfTheSessionsResourceProtected() throws Exception {
        mvc.perform(get("/api/v1/sessions")).andExpect(status().isUnauthorized());
    }

    private String tokenThatIs(BadToken bad) {
        // Real time is fine here: every instant is hours away from the limit being tested.
        var now = Instant.now();
        var valid =
                JwtClaimsSet.builder()
                        .issuer(JwtClaimNames.ISSUER)
                        .subject(UUID.randomUUID().toString())
                        .issuedAt(now)
                        .expiresAt(now.plus(Duration.ofMinutes(15)))
                        .claim(JwtClaimNames.ROLE, "CITIZEN")
                        .claim(JwtClaimNames.SESSION_ID, UUID.randomUUID().toString());
        var accessTokenHeader =
                JwsHeader.with(MacAlgorithm.HS256).type(JwtClaimNames.ACCESS_TOKEN_TYPE).build();
        return switch (bad) {
            case MALFORMED -> "not-a-jwt";
            case UNSIGNED -> unsigned(sign(encoder, accessTokenHeader, valid.build()));
            case SIGNED_WITH_ANOTHER_SECRET ->
                    sign(
                            new NimbusJwtEncoder(
                                    new ImmutableSecret<>(
                                            "another-secret-of-at-least-32-bytes!"
                                                    .getBytes(StandardCharsets.UTF_8))),
                            accessTokenHeader,
                            valid.build());
            case EXPIRED ->
                    sign(
                            encoder,
                            accessTokenHeader,
                            valid.issuedAt(now.minus(Duration.ofHours(2)))
                                    .expiresAt(now.minus(Duration.ofHours(1)))
                                    .build());
            case WITHOUT_EXPIRY ->
                    sign(encoder, accessTokenHeader, valid.claims(c -> c.remove("exp")).build());
            case NOT_AN_ACCESS_TOKEN ->
                    sign(encoder, JwsHeader.with(MacAlgorithm.HS256).build(), valid.build());
            case UNKNOWN_ROLE ->
                    sign(
                            encoder,
                            accessTokenHeader,
                            valid.claim(JwtClaimNames.ROLE, "SUPERUSER").build());
            case SUBJECT_NOT_A_UUID ->
                    sign(encoder, accessTokenHeader, valid.subject("admin").build());
        };
    }

    private static String sign(JwtEncoder encoder, JwsHeader header, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Same payload with the header {@code "alg":"none"} and no signature. */
    private static String unsigned(String signedToken) {
        var payload = signedToken.split("\\.")[1];
        var header =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"alg\":\"none\",\"typ\":\"at+jwt\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    @RestController
    static class ProbeController {

        @GetMapping(ME)
        AuthenticatedUser me(@AuthenticationPrincipal AuthenticatedUser user) {
            return user;
        }

        @GetMapping(ADMIN_ONLY)
        @PreAuthorize("hasRole('ADMINISTRATOR')")
        String adminOnly() {
            return "ok";
        }
    }
}
