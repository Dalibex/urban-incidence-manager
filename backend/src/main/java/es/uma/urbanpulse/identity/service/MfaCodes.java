package es.uma.urbanpulse.identity.service;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * TOTP codes of the second factor (RFC 6238 with its defaults: HMAC-SHA-1, 6 digits, 30-second
 * steps), the ones every authenticator app shows. The library computes a code; the window of one
 * step on each side, which covers a phone clock a little off, and the refusal of a used code are
 * here.
 */
@Component
class MfaCodes {

    // 160 bits, the length of an HMAC-SHA-1 output (RFC 4226, section 4).
    private static final int SECRET_BYTES = 20;
    private static final long STEP_SECONDS =
            TimeBasedOneTimePasswordGenerator.DEFAULT_TIME_STEP.toSeconds();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;

    MfaCodes(Clock clock) {
        this.clock = clock;
    }

    byte[] newSecret() {
        var secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /**
     * The time step of {@code code} if it is the code of the current step or of the one before or
     * after it, and that step is later than {@code lastAcceptedStep}; empty otherwise. Storing the
     * returned step as the new last one refuses the same code, and any older one, from then on.
     *
     * @param lastAcceptedStep {@code null} when no code was accepted yet
     */
    OptionalLong acceptedStep(byte[] secret, String code, Long lastAcceptedStep) {
        // A new generator each time: the library does not document it as thread-safe.
        var generator = new TimeBasedOneTimePasswordGenerator();
        var key = new SecretKeySpec(secret, generator.getAlgorithm());
        var currentStep = Math.floorDiv(clock.instant().getEpochSecond(), STEP_SECONDS);
        for (var step = currentStep - 1; step <= currentStep + 1; step++) {
            if (lastAcceptedStep != null && step <= lastAcceptedStep) {
                continue;
            }
            if (sameCode(code, codeAt(generator, key, step))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * The {@code otpauth://} URI an authenticator app reads from a QR code, labelled with the
     * issuer and the email of the account.
     */
    static String otpauthUri(String issuer, String email, byte[] secret) {
        return "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=%d"
                .formatted(
                        uriEncode(issuer),
                        uriEncode(email),
                        base32(secret),
                        uriEncode(issuer),
                        STEP_SECONDS);
    }

    /** RFC 4648 Base32 without padding, the form authenticator apps take typed by hand. */
    static String base32(byte[] bytes) {
        var text = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (var b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                text.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            text.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return text.toString();
    }

    private static String codeAt(
            TimeBasedOneTimePasswordGenerator generator, SecretKeySpec key, long step) {
        try {
            return generator.generateOneTimePasswordString(
                    key, Instant.ofEpochSecond(step * STEP_SECONDS), Locale.ROOT);
        } catch (InvalidKeyException e) {
            // Any byte array is a valid HMAC key.
            throw new IllegalStateException("Invalid second-factor secret", e);
        }
    }

    // URLEncoder writes a space as "+", which is only a space in a query, not in the label.
    private static String uriEncode(String text) {
        return URLEncoder.encode(text, UTF_8).replace("+", "%20");
    }

    // Constant time, so the response time does not tell how many digits were right.
    private static boolean sameCode(String presented, String expected) {
        return MessageDigest.isEqual(presented.getBytes(US_ASCII), expected.getBytes(US_ASCII));
    }
}
