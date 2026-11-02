package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.service.RefreshTokenCleanup;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance test of #140, T10: the clean-up of #125 follows {@code REFRESH_TOKEN_RETENTION}, not
 * only its default of 30 days. The application clock is fixed, so the cut-off does not depend on
 * when the test runs.
 */
@SpringBootTest(properties = "REFRESH_TOKEN_RETENTION=P7D")
@Import({TestcontainersConfiguration.class, RefreshTokenRetentionIT.FixedClock.class})
class RefreshTokenRetentionIT {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefreshTokenCleanup cleanup;

    @Test
    void theCleanupUsesTheConfiguredRetention() {
        var user = citizen();
        var eightDaysAgo = token(user, NOW.minus(Duration.ofDays(8)));
        var sixDaysAgo = token(user, NOW.minus(Duration.ofDays(6)));
        // Not expired yet: no retention deletes it.
        var stillValid = token(user, NOW.plus(Duration.ofDays(5)));

        cleanup.deleteExpiredTokens();

        assertThat(isStored(eightDaysAgo)).isFalse();
        assertThat(isStored(sixDaysAgo)).isTrue();
        assertThat(isStored(stillValid)).isTrue();
    }

    private UUID citizen() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash, role) VALUES (?, 'Ana', ?, 'x',"
                        + " 'CITIZEN')",
                id,
                "retention-" + id + "@example.com");
        return id;
    }

    private UUID token(UUID user, Instant expiresAt) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO refresh_token (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                id,
                user,
                id.toString(),
                Timestamp.from(expiresAt));
        return id;
    }

    private boolean isStored(UUID token) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE id = ?", Integer.class, token)
                == 1;
    }
}
