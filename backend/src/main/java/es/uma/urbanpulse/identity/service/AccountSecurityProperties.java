package es.uma.urbanpulse.identity.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lifetime of the refresh tokens and the rate limits of sign-in and sign-up (docs/security.md).
 *
 * @param refreshTokenTtl how long a refresh token works ({@code REFRESH_TOKEN_TTL})
 * @param signInAttempts failed sign-ins allowed per email and client address in each window
 * @param signUpAttempts sign-ups allowed per client address in each window
 */
@ConfigurationProperties("urbanpulse.security")
public record AccountSecurityProperties(
        Duration refreshTokenTtl, AttemptLimit signInAttempts, AttemptLimit signUpAttempts) {

    public AccountSecurityProperties {
        requirePositive(refreshTokenTtl, "urbanpulse.security.refresh-token-ttl");
        if (signInAttempts == null || signUpAttempts == null) {
            throw new IllegalArgumentException(
                    "urbanpulse.security.sign-in-attempts and sign-up-attempts are required");
        }
    }

    /** At most {@code max} attempts in each {@code window}. */
    public record AttemptLimit(int max, Duration window) {

        public AttemptLimit {
            if (max < 1) {
                throw new IllegalArgumentException("An attempt limit needs max >= 1");
            }
            requirePositive(window, "an attempt limit window");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
