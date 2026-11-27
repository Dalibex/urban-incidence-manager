package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The second factor of the staff (#57, ADR-0021), read from the environment: {@code
 * MFA_REQUIRED_ROLES}, {@code MFA_ENCRYPTION_KEY} and {@code MFA_ISSUER}.
 *
 * <p>The checks run when the application starts, so a missing or malformed key stops the start
 * instead of storing secrets that cannot be read back. No message prints the key. {@code CITIZEN}
 * cannot be listed, and an empty list starts with a warning that the second factor is off.
 *
 * @param requiredRoles the staff roles whose sign-in asks for a code; empty, nobody's does
 * @param encryptionKey Base64 of the 32-byte AES-256 key that encrypts the TOTP secrets
 * @param issuer the name the authenticator app shows next to the account
 * @param challengeTtl how long a sign-in challenge works
 * @param codeAttemptsPerChallenge wrong codes that spend a challenge
 * @param codeAttempts wrong codes allowed per account in each window
 * @param maxConsecutiveWrongCodes consecutive wrong codes after which the code step of the account
 *     is refused until an administrator resets it (#435); at most {@value
 *     #MAX_CONSECUTIVE_WRONG_CODES}, as NIST SP 800-63B (5.2.2) asks
 */
@ConfigurationProperties("urbanpulse.security.mfa")
public record MfaProperties(
        Set<Role> requiredRoles,
        String encryptionKey,
        String issuer,
        Duration challengeTtl,
        int codeAttemptsPerChallenge,
        AttemptLimit codeAttempts,
        @DefaultValue("100") int maxConsecutiveWrongCodes) {

    // AES-256.
    static final int KEY_BYTES = 32;

    /** The most consecutive failed attempts NIST SP 800-63B (5.2.2) allows on an authenticator. */
    static final int MAX_CONSECUTIVE_WRONG_CODES = 100;

    private static final Logger log = LoggerFactory.getLogger(MfaProperties.class);

    public MfaProperties {
        requiredRoles = requiredRoles == null ? Set.of() : Set.copyOf(requiredRoles);
        if (requiredRoles.contains(Role.CITIZEN)) {
            throw new IllegalArgumentException(
                    "MFA_REQUIRED_ROLES must not include CITIZEN: citizens sign in with the mobile"
                            + " app, which has no step for a code (ADR-0021)");
        }
        if (requiredRoles.isEmpty()) {
            log.warn("MFA_REQUIRED_ROLES is empty: no sign-in asks for a second factor");
        }
        decodeKey(encryptionKey);
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("MFA_ISSUER must not be blank");
        }
        if (challengeTtl == null || challengeTtl.isZero() || challengeTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "urbanpulse.security.mfa.challenge-ttl must be a positive duration");
        }
        if (codeAttemptsPerChallenge < 1) {
            throw new IllegalArgumentException(
                    "urbanpulse.security.mfa.code-attempts-per-challenge must be at least 1");
        }
        if (codeAttempts == null) {
            throw new IllegalArgumentException("urbanpulse.security.mfa.code-attempts is required");
        }
        if (maxConsecutiveWrongCodes < 1
                || maxConsecutiveWrongCodes > MAX_CONSECUTIVE_WRONG_CODES) {
            throw new IllegalArgumentException(
                    "urbanpulse.security.mfa.max-consecutive-wrong-codes must be between 1 and "
                            + MAX_CONSECUTIVE_WRONG_CODES);
        }
    }

    /** Whether a sign-in with this role asks for a code. */
    public boolean requiredFor(Role role) {
        return requiredRoles.contains(role);
    }

    /** The bytes of the AES key, already checked at start. */
    byte[] encryptionKeyBytes() {
        return decodeKey(encryptionKey);
    }

    private static byte[] decodeKey(String encryptionKey) {
        var invalid =
                new IllegalArgumentException(
                        "MFA_ENCRYPTION_KEY must be the Base64 of exactly "
                                + KEY_BYTES
                                + " bytes. Generate one with: openssl rand -base64 32");
        if (encryptionKey == null) {
            throw invalid;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKey.strip());
        } catch (IllegalArgumentException e) {
            // Not chained: the decoder's message quotes a character of the key.
            throw invalid;
        }
        if (key.length != KEY_BYTES) {
            throw invalid;
        }
        return key;
    }

    /** Keeps the key out of logs and error messages that print the bound properties. */
    @Override
    public String toString() {
        return "MfaProperties[requiredRoles="
                + requiredRoles
                + ", encryptionKey=***, issuer="
                + issuer
                + ", challengeTtl="
                + challengeTtl
                + ", codeAttemptsPerChallenge="
                + codeAttemptsPerChallenge
                + ", codeAttempts="
                + codeAttempts
                + ", maxConsecutiveWrongCodes="
                + maxConsecutiveWrongCodes
                + "]";
    }
}
