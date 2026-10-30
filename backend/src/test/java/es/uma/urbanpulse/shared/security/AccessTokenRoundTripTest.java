package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.AccessTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Issues tokens with {@link AccessTokenService} and reads them back with the decoder and converter
 * of the security filter chain, with controlled clocks instead of the real time.
 */
class AccessTokenRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final JwtProperties PROPERTIES =
            new JwtProperties("unit-test-secret-of-at-least-32-bytes", TTL);

    private final JwtConfig config = new JwtConfig();
    private final AccessTokenService issuer =
            new AccessTokenService(config.jwtEncoder(PROPERTIES), PROPERTIES, clockAt(NOW));

    @Test
    void carriesTheUserRoleAndSessionAndExpiresAfterTheConfiguredLifetime() {
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();

        var token = issuer.issue(userId, Role.MUNICIPAL_OPERATOR, sessionId);
        var jwt = decoderAt(NOW).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(JwtClaimNames.ISSUER);
        assertThat(jwt.getClaimAsString(JwtClaimNames.ROLE)).isEqualTo("MUNICIPAL_OPERATOR");
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(TTL)).isEqualTo(token.expiresAt());

        var authentication = new AuthenticatedUserConverter().convert(jwt);
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AuthenticatedUser(userId, Role.MUNICIPAL_OPERATOR, sessionId));
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_MUNICIPAL_OPERATOR");
    }

    @Test
    void isRejectedOnceExpired() {
        var token = issuer.issue(UUID.randomUUID(), Role.CITIZEN, UUID.randomUUID());

        // Past the lifetime plus the 60 s of clock skew that the timestamp validator tolerates.
        var later = NOW.plus(TTL).plusSeconds(61);

        assertThatThrownBy(() -> decoderAt(later).decode(token.value()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void isRejectedWhenSignedWithAnotherSecret() {
        var otherProperties = new JwtProperties("another-secret-of-at-least-32-bytes!", TTL);
        var forger =
                new AccessTokenService(
                        config.jwtEncoder(otherProperties), otherProperties, clockAt(NOW));
        var forged = forger.issue(UUID.randomUUID(), Role.ADMINISTRATOR, UUID.randomUUID());

        assertThatThrownBy(() -> decoderAt(NOW).decode(forged.value()))
                .hasMessageContaining("signature");
    }

    @Test
    void isRejectedWhenAnotherIssuerSignedIt() {
        var token =
                sign(claims().issuer("someone-else").claim(JwtClaimNames.ROLE, "CITIZEN").build());

        assertThatThrownBy(() -> decoderAt(NOW).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void isRejectedWithoutExpiry() {
        var token =
                sign(
                        JwtClaimsSet.builder()
                                .issuer(JwtClaimNames.ISSUER)
                                .subject(UUID.randomUUID().toString())
                                .issuedAt(NOW)
                                .claim(JwtClaimNames.ROLE, "CITIZEN")
                                .claim(JwtClaimNames.SESSION_ID, UUID.randomUUID().toString())
                                .build());

        assertThatThrownBy(() -> decoderAt(NOW).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void isRejectedWithoutTheAccessTokenType() {
        var token =
                sign(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims().claim(JwtClaimNames.ROLE, "CITIZEN").build());

        assertThatThrownBy(() -> decoderAt(NOW).decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void isRejectedWithoutSessionId() {
        var token =
                sign(
                        JwtClaimsSet.builder()
                                .issuer(JwtClaimNames.ISSUER)
                                .subject(UUID.randomUUID().toString())
                                .issuedAt(NOW)
                                .expiresAt(NOW.plus(TTL))
                                .claim(JwtClaimNames.ROLE, "CITIZEN")
                                .build());

        assertThatThrownBy(() -> decoderAt(NOW).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void anUnknownRoleIsAnInvalidTokenNotAServerError() {
        var jwt =
                decoderAt(NOW)
                        .decode(sign(claims().claim(JwtClaimNames.ROLE, "SUPERUSER").build()));

        assertThatThrownBy(() -> new AuthenticatedUserConverter().convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void aSubjectThatIsNotAUuidIsAnInvalidToken() {
        var jwt =
                decoderAt(NOW)
                        .decode(
                                sign(
                                        claims().subject("admin")
                                                .claim(JwtClaimNames.ROLE, "CITIZEN")
                                                .build()));

        assertThatThrownBy(() -> new AuthenticatedUserConverter().convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void neverPrintsTheTokenValue() {
        var token = issuer.issue(UUID.randomUUID(), Role.CITIZEN, UUID.randomUUID());

        assertThat(token.toString()).doesNotContain(token.value());
    }

    private static JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder()
                .issuer(JwtClaimNames.ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plus(TTL))
                .claim(JwtClaimNames.SESSION_ID, UUID.randomUUID().toString());
    }

    private String sign(JwtClaimsSet claims) {
        return sign(
                JwsHeader.with(MacAlgorithm.HS256).type(JwtClaimNames.ACCESS_TOKEN_TYPE).build(),
                claims);
    }

    private String sign(JwsHeader header, JwtClaimsSet claims) {
        return config.jwtEncoder(PROPERTIES)
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }

    private JwtDecoder decoderAt(Instant instant) {
        return config.jwtDecoder(PROPERTIES, clockAt(instant));
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
