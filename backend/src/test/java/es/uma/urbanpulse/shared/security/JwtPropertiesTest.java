package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static final String SECRET = "a-secret-of-exactly-thirty-two-b";

    @Test
    void acceptsASecretOf256Bits() {
        assertThat(new JwtProperties(SECRET, Duration.ofMinutes(15)).secret()).hasSize(32);
    }

    @Test
    void rejectsAMissingSecret() {
        assertThatThrownBy(() -> new JwtProperties(null, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsASecretShorterThan256Bits() {
        assertThatThrownBy(() -> new JwtProperties(SECRET.substring(1), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsANonPositiveLifetime() {
        assertThatThrownBy(() -> new JwtProperties(SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_ACCESS_TOKEN_TTL");
    }

    @Test
    void neverPrintsTheSecret() {
        assertThat(new JwtProperties(SECRET, Duration.ofMinutes(15)).toString())
                .doesNotContain(SECRET);
    }
}
