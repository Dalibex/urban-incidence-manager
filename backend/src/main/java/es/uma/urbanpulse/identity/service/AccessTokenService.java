package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.JwtClaimNames;
import es.uma.urbanpulse.shared.security.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the short-lived access tokens of ADR-0004 (about 15 minutes, {@code
 * JWT_ACCESS_TOKEN_TTL}).
 */
@Service
public class AccessTokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Signs an access token for a user.
     *
     * @param sessionId id of the refresh token issued together with this access token
     */
    public AccessToken issue(UUID userId, Role role, UUID sessionId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        var claims =
                JwtClaimsSet.builder()
                        .issuer(JwtClaimNames.ISSUER)
                        .subject(userId.toString())
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .claim(JwtClaimNames.ROLE, role.name())
                        .claim(JwtClaimNames.SESSION_ID, sessionId.toString())
                        .build();
        var header =
                JwsHeader.with(MacAlgorithm.HS256).type(JwtClaimNames.ACCESS_TOKEN_TYPE).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    /** A signed access token and the instant it stops being valid. */
    public record AccessToken(String value, Instant expiresAt) {

        /** Keeps the token out of logs: whoever reads it can use it until it expires. */
        @Override
        public String toString() {
            return "AccessToken[value=***, expiresAt=" + expiresAt + "]";
        }
    }
}
