package es.uma.urbanpulse.identity.service;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MfaSecretCipherTest {

    private static final String KEY = "dGVzdC1rZXktb2YtdGhlLXNlY29uZC1mYWN0b3ItMzI=";
    private static final String OTHER_KEY = "YW5vdGhlci1rZXktb2YtZXhhY3RseS0zMi1ieXRlcyE=";
    private static final byte[] SECRET = "12345678901234567890".getBytes(US_ASCII);

    private final MfaSecretCipher cipher = cipherWith(KEY);

    @Test
    void decryptsWhatItEncrypted() {
        var stored = cipher.encrypt(SECRET);

        assertThat(cipher.decrypt(stored)).isEqualTo(SECRET);
    }

    @Test
    void neverStoresTheSecretInTheClear() {
        var stored = cipher.encrypt(SECRET);

        var hex = HexFormat.of();
        assertThat(hex.formatHex(Base64.getDecoder().decode(stored)))
                .doesNotContain(hex.formatHex(SECRET));
    }

    @Test
    void encryptsTheSameSecretDifferentlyEachTime() {
        assertThat(cipher.encrypt(SECRET)).isNotEqualTo(cipher.encrypt(SECRET));
    }

    @Test
    void refusesATextEncryptedWithAnotherKey() {
        var stored = cipherWith(OTHER_KEY).encrypt(SECRET);

        assertThatIllegalStateException()
                .isThrownBy(() -> cipher.decrypt(stored))
                .withMessageContaining("MFA_ENCRYPTION_KEY");
    }

    @Test
    void refusesAChangedText() {
        var bytes = Base64.getDecoder().decode(cipher.encrypt(SECRET));
        bytes[bytes.length - 1] ^= 1;
        var changed = Base64.getEncoder().encodeToString(bytes);

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(changed));
    }

    private static MfaSecretCipher cipherWith(String key) {
        return new MfaSecretCipher(
                new MfaProperties(
                        Set.of(Role.ADMINISTRATOR),
                        key,
                        "UrbanPulse",
                        Duration.ofMinutes(5),
                        5,
                        new AttemptLimit(10, Duration.ofMinutes(15))));
    }
}
