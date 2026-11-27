package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.notifications.service.PushTokenCleanup;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #373, criterion 7: a push token not registered again for 60 days is deleted by a scheduled job
 * (plan of #373, section 4: {@code last_registered_at < now - 60 days}). The test calls the job
 * instead of waiting for its cron, and the application clock is fixed, so the cut-off does not
 * depend on when the test runs.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, PushTokenCleanupIT.FixedClock.class})
@DisplayName("PushTokenCleanup, deleting the tokens of devices gone quiet")
class PushTokenCleanupIT {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PushTokenCleanup cleanup;

    private UUID citizen;

    @BeforeEach
    void createCitizen() {
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN')"
                                + " RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        jdbc.update("DELETE FROM push_token WHERE user_id = ?", citizen);
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    @ParameterizedTest(name = "last registered {0} ago -> kept: {1}")
    @CsvSource({"P61D, false", "P60DT1S, false", "P60D, true", "P59D, true"})
    @DisplayName("deletes the tokens last registered more than 60 days ago, and keeps the rest")
    void deletesTheTokensOlderThanSixtyDays(String age, boolean kept) {
        // Arrange
        String token = device(NOW.minus(Duration.parse(age)));

        // Act
        cleanup.deleteStale();

        // Assert
        assertThat(isStored(token)).isEqualTo(kept);
    }

    private String device(Instant lastRegisteredAt) {
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        jdbc.update(
                "INSERT INTO push_token (user_id, token, created_at, last_registered_at)"
                        + " VALUES (?, ?, ?, ?)",
                citizen,
                token,
                Timestamp.from(lastRegisteredAt.minus(Duration.ofDays(90))),
                Timestamp.from(lastRegisteredAt));
        return token;
    }

    private boolean isStored(String token) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM push_token WHERE token = ?", Integer.class, token)
                == 1;
    }
}
