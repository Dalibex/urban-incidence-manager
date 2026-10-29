package es.uma.urbanpulse.shared.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing secret and lifetimes of the access tokens (ADR-0004), read from the environment
 * (Twelve-Factor III): {@code JWT_SECRET} and {@code JWT_ACCESS_TOKEN_TTL}.
 *
 * <p>The checks run when the application starts, so a missing or weak secret stops the start
 * instead of signing tokens that anyone could forge.
 */
@ConfigurationProperties("urbanpulse.security.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {

    // HS256 needs a key of at least 256 bits (RFC 7518, section 3.2).
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must have at least "
                            + MIN_SECRET_BYTES
                            + " bytes. Generate one with: openssl rand -base64 48");
        }
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("JWT_ACCESS_TOKEN_TTL must be a positive duration");
        }
    }

    /** Keeps the secret out of logs and error messages that print the bound properties. */
    @Override
    public String toString() {
        return "JwtProperties[secret=***, accessTokenTtl=" + accessTokenTtl + "]";
    }
}
