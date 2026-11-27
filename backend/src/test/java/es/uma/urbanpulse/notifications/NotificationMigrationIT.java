package es.uma.urbanpulse.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #373, existing users: the citizens who signed up before the notification channels existed keep
 * hearing about their incidents by email, and push stays off until they turn it on in the app
 * (plan, section 1; ADR-0031). The schema is migrated up to the version before the channels, a user
 * is stored there, and then the rest of the migrations run, as on a database upgraded in place.
 */
@DisplayName("The notification migration on a database upgraded in place")
class NotificationMigrationIT {

    // Same image as TestcontainersConfiguration and docker-compose.yaml.
    private static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(
                    DockerImageName.parse("postgis/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"));

    /** The last migration before the notification channels of V23. */
    private static final String BEFORE_THE_CHANNELS = "22";

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startTheDatabase() {
        DATABASE.start();
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword()));
    }

    @AfterAll
    static void stopTheDatabase() {
        DATABASE.stop();
    }

    @Test
    @DisplayName("an existing user gets email on and push off")
    void anExistingUserGetsEmailOnAndPushOff() {
        // Arrange
        flyway(BEFORE_THE_CHANNELS).migrate();
        UUID citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', 'citizen@example.com',"
                                + " '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                        UUID.class);

        // Act
        flyway(null).migrate();

        // Assert
        assertThat(
                        jdbc.queryForMap(
                                "SELECT notify_by_push, notify_by_email FROM users WHERE id = ?",
                                citizen))
                .containsEntry("notify_by_push", false)
                .containsEntry("notify_by_email", true);
    }

    /** The versioned migrations of the backend, up to {@code target}, or all of them if null. */
    private static Flyway flyway(String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword())
                        .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
