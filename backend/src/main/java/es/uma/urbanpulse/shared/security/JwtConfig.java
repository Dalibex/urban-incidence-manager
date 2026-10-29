package es.uma.urbanpulse.shared.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.time.Clock;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs and validates the access tokens with HS256 and the secret {@code JWT_SECRET}.
 *
 * <p>A symmetric key is enough because the same application issues and validates the tokens
 * (ADR-0010). If another service ever had to validate them, it would need the secret, and RS256
 * with a public key would be the better choice.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        // Fixed algorithm: a token whose header asks for another one, or for "none", is rejected.
        // Fixed type: a JWT signed for another purpose (a future password reset link, say) is not
        // an access token even if it carries the same claims (RFC 8725, section 3.11).
        var decoder =
                NimbusJwtDecoder.withSecretKey(signingKey(properties))
                        .macAlgorithm(ALGORITHM)
                        .jwtProcessorCustomizer(
                                processor ->
                                        processor.setJWSTypeVerifier(
                                                new DefaultJOSEObjectTypeVerifier<>(
                                                        new JOSEObjectType(
                                                                JwtClaimNames.ACCESS_TOKEN_TYPE))))
                        .build();

        var timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        // By default a token without `exp` would never expire; ADR-0004 relies on the expiry.
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        List.of(
                                timestamps,
                                new JwtIssuerValidator(JwtClaimNames.ISSUER),
                                new JwtClaimValidator<>(
                                        JwtClaimNames.SESSION_ID, sid -> sid != null))));
        return decoder;
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(UTF_8), ALGORITHM.getName());
    }
}
