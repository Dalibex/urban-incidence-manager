package es.uma.urbanpulse.geospatial;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #375, existing incidents: the incidents stored before the address worker existed are shown as
 * unavailable, not looked up one by one (the option the pull request chose). The schema is migrated
 * up to the version before the address status, a location is stored there, and then the rest of the
 * migrations run, as on a database that is upgraded in place.
 */
class AddressMigrationIT {

    // Same image as TestcontainersConfiguration and docker-compose.yaml.
    private static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(
                    DockerImageName.parse("postgis/postgis:18-3.6")
                            .asCompatibleSubstituteFor("postgres"));

    /** The last migration before the address status of V19. */
    private static final String BEFORE_THE_ADDRESS_STATUS = "18";

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
    void theLocationsStoredBeforeTheAddressStatusBecomeUnavailable() {
        flyway(BEFORE_THE_ADDRESS_STATUS).migrate();
        UUID citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', 'citizen@example.com',"
                                + " '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                        UUID.class);
        UUID withAnAddress = storedIncident(citizen, "Calle Larios, 5");
        UUID withoutAnAddress = storedIncident(citizen, null);

        flyway(null).migrate();

        for (UUID incident : new UUID[] {withAnAddress, withoutAnAddress}) {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT address, address_status, address_source, address_resolved_at"
                                    + " FROM location WHERE incident_id = ?",
                            incident);
            // An address written before there was a source to say where it came from is not
            // shown as current.
            assertThat(row)
                    .containsEntry("address_status", "UNAVAILABLE")
                    .containsEntry("address", null)
                    .containsEntry("address_source", null)
                    .containsEntry("address_resolved_at", null);
        }
        // A location stored after the migration waits for the worker.
        UUID newIncident = storedIncident(citizen, null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT address_status FROM location WHERE incident_id = ?",
                                String.class,
                                newIncident))
                .isEqualTo("PENDING");
    }

    private static UUID storedIncident(UUID reporter, String address) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at) VALUES (?, 'Broken streetlight',"
                                + " 'The lamp is off.', 'REPORTED', now(), now()) RETURNING id",
                        UUID.class,
                        reporter);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates, address) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography, ?)",
                incident,
                address);
        return incident;
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
