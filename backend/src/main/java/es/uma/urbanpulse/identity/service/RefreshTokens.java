package es.uma.urbanpulse.identity.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque refresh tokens: 256 random bits, of which the database only keeps a SHA-256 hash
 * (ADR-0004). A fast hash is enough here, unlike passwords: nobody can guess 256 random bits, and
 * the hash has to be deterministic to find the token by it.
 */
final class RefreshTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokens() {}

    static String generate() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must provide SHA-256 (MessageDigest javadoc).
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
