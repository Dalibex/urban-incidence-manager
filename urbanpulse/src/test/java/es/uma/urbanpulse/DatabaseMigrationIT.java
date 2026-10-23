package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The schema is built only by Flyway, on an empty PostGIS database (ADR-0014). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DatabaseMigrationIT {

    private static final List<String> SCHEMA_TABLES =
            List.of(
                    "users",
                    "refresh_token",
                    "audit_log",
                    "category",
                    "department",
                    "incident",
                    "location",
                    "status_change",
                    "assignment",
                    "comment",
                    "attachment",
                    "urban_context",
                    "external_observation",
                    "urban_asset",
                    "incident_asset",
                    "data_source_config",
                    "notification");

    @Autowired private Flyway flyway;

    @Autowired private JdbcTemplate jdbc;

    @Test
    void appliesEveryMigrationWithoutPendingOrFailedOnes() {
        var info = flyway.info();

        assertThat(info.applied())
                .isNotEmpty()
                .allMatch(migration -> migration.getState().isApplied());
        assertThat(info.pending()).isEmpty();
    }

    @Test
    void enablesPostgis() {
        var extensions =
                jdbc.queryForList(
                        "SELECT extname FROM pg_extension WHERE extname = 'postgis'", String.class);

        assertThat(extensions).containsExactly("postgis");
    }

    @Test
    void createsEveryTableOfTheSchema() {
        var tables =
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                        String.class);

        assertThat(tables).containsAll(SCHEMA_TABLES);
    }

    @Test
    void rejectsAnUnknownEnumValue() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO users (name, email, password_hash, role)"
                                                + " VALUES ('Ana', 'ana@example.com', 'hash', 'SUPERUSER')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAnIncidentWhoseReporterDoesNotExist() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO incident (reporter_id, title, description, status,"
                                                + " reported_at, updated_at)"
                                                + " VALUES (?, 'Pothole', 'Deep pothole', 'REPORTED', now(), now())",
                                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
