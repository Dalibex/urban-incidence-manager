package es.uma.urbanpulse.identity.service;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The time window and the reuse check of the second factor, with the SHA-1 test vector of RFC 6238
 * (appendix B): at 1111111109 s the 8-digit code is 07081804, so the 6-digit one is 081804.
 */
class MfaCodesTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(US_ASCII);
    private static final long VECTOR_SECOND = 1_111_111_109L;
    private static final long VECTOR_STEP = VECTOR_SECOND / 30;
    private static final String VECTOR_CODE = "081804";

    @ParameterizedTest(name = "{0} s from the code's own time")
    @ValueSource(longs = {-30, 0, 30})
    void acceptsTheCodeOfTheCurrentStepAndOfOneStepOnEachSide(long offsetSeconds) {
        var codes = codesAt(VECTOR_SECOND + offsetSeconds);

        assertThat(codes.acceptedStep(SECRET, VECTOR_CODE, null)).hasValue(VECTOR_STEP);
    }

    @ParameterizedTest(name = "{0} s from the code's own time")
    @ValueSource(longs = {-60, 60})
    void refusesTheCodeTwoStepsAway(long offsetSeconds) {
        var codes = codesAt(VECTOR_SECOND + offsetSeconds);

        assertThat(codes.acceptedStep(SECRET, VECTOR_CODE, null)).isEmpty();
    }

    @Test
    void refusesAWrongCode() {
        var codes = codesAt(VECTOR_SECOND);

        assertThat(codes.acceptedStep(SECRET, "081805", null)).isEmpty();
    }

    @Test
    void refusesACodeAlreadyUsed() {
        var codes = codesAt(VECTOR_SECOND);

        assertThat(codes.acceptedStep(SECRET, VECTOR_CODE, VECTOR_STEP)).isEmpty();
    }

    @Test
    void refusesACodeOlderThanTheLastOneUsed() {
        // The code of the next step was used first, within the window; the older one stays refused.
        var codes = codesAt(VECTOR_SECOND);

        assertThat(codes.acceptedStep(SECRET, VECTOR_CODE, VECTOR_STEP + 1)).isEmpty();
    }

    @Test
    void acceptsTheCodeOfAStepAfterTheLastOneUsed() {
        var codes = codesAt(VECTOR_SECOND);

        assertThat(codes.acceptedStep(SECRET, VECTOR_CODE, VECTOR_STEP - 1)).hasValue(VECTOR_STEP);
    }

    @Test
    void writesTheSecretInBase32WithoutPadding() {
        // RFC 4648, section 10: "foobar" is MZXW6YTBOI======.
        assertThat(MfaCodes.base32("foobar".getBytes(US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void buildsTheOtpauthUriWithTheIssuerTheEmailAndTheSecret() {
        var uri = MfaCodes.otpauthUri("Urban Pulse", "ana+staff@example.com", SECRET);

        assertThat(uri)
                .isEqualTo(
                        "otpauth://totp/Urban%20Pulse:ana%2Bstaff%40example.com"
                                + "?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Urban%20Pulse"
                                + "&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    void createsADifferentSecretOf160BitsEachTime() {
        var codes = codesAt(VECTOR_SECOND);

        var first = codes.newSecret();
        var second = codes.newSecret();

        assertThat(first).hasSize(20).isNotEqualTo(second);
    }

    private static MfaCodes codesAt(long epochSecond) {
        return new MfaCodes(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }
}
