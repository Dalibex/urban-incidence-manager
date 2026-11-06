package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
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

/**
 * The schema and the reference data are built only by Flyway, on an empty PostGIS database
 * (ADR-0014).
 */
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

    private static final List<String> REFERENCE_CATEGORIES =
            List.of(
                    "Street lighting",
                    "Pavement",
                    "Waste",
                    "Urban furniture",
                    "Parks and green areas",
                    "Signage",
                    "Other");

    private static final List<String> REFERENCE_DEPARTMENTS =
            List.of(
                    "Public Lighting",
                    "Roads and Works",
                    "Cleaning and Waste",
                    "Parks and Gardens",
                    "Mobility");

    // The indexes of the main queries (#132), measured in docs/database.md, "Indexes".
    private static final List<String> MAIN_QUERY_INDEXES =
            List.of(
                    "ix_incident_reporter_reported_at",
                    "ix_incident_reported_at",
                    "ix_incident_status_reported_at",
                    "ix_assignment_active_technician",
                    "ix_status_change_incident_id");

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
    void createsTheIndexesOfTheMainQueries() {
        var indexes =
                jdbc.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                        String.class);

        assertThat(indexes).containsAll(MAIN_QUERY_INDEXES);
    }

    @Test
    void loadsTheActiveReferenceCategories() {
        var categories =
                jdbc.queryForList("SELECT name FROM category WHERE is_active", String.class);

        assertThat(categories).containsExactlyInAnyOrderElementsOf(REFERENCE_CATEGORIES);
    }

    @Test
    void givesEveryReferenceCategoryAHelpText() {
        var withoutDescription =
                jdbc.queryForObject(
                        "SELECT count(*) FROM category WHERE description IS NULL OR description = ''",
                        Integer.class);

        assertThat(withoutDescription).isZero();
    }

    @Test
    void loadsTheActiveReferenceDepartments() {
        var departments =
                jdbc.queryForList("SELECT name FROM department WHERE is_active", String.class);

        assertThat(departments).containsExactlyInAnyOrderElementsOf(REFERENCE_DEPARTMENTS);
    }

    @Test
    void loadsNoDevelopmentDataWithoutTheDevProfile() {
        var repeatable =
                Arrays.stream(flyway.info().all())
                        .filter(migration -> migration.getVersion() == null);
        var developmentUsers =
                jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE id::text LIKE 'd0000000-%'",
                        Integer.class);
        var developmentIncidents =
                jdbc.queryForObject(
                        "SELECT count(*) FROM incident WHERE id::text LIKE 'd1000000-%'",
                        Integer.class);

        assertThat(repeatable).isEmpty();
        assertThat(developmentUsers).isZero();
        assertThat(developmentIncidents).isZero();
    }

    @Test
    void loadsNoDemoDataWithoutTheDemoProfile() {
        var demoUsers =
                jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE id::text LIKE 'e0000000-%'",
                        Integer.class);
        var demoIncidents =
                jdbc.queryForObject(
                        "SELECT count(*) FROM incident WHERE id::text LIKE 'e1000000-%'",
                        Integer.class);

        assertThat(demoUsers).isZero();
        assertThat(demoIncidents).isZero();
    }

    @Test
    void loadsNoPerformanceDataWithoutThePerfProfile() {
        var performanceUsers =
                jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE id::text LIKE 'f0000000-%'",
                        Integer.class);
        var performanceIncidents =
                jdbc.queryForObject(
                        "SELECT count(*) FROM incident WHERE id::text LIKE 'f1000000-%'",
                        Integer.class);

        assertThat(performanceUsers).isZero();
        assertThat(performanceIncidents).isZero();
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

    // #192: two uploads of the same content to one incident cannot both be stored.
    @Test
    void rejectsTheSameContentTwiceOnOneIncident() {
        UUID reporter =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Ana', 'ana@example.com', 'hash', 'CITIZEN') RETURNING id",
                        UUID.class);
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Pothole', 'Deep pothole', 'REPORTED',"
                                + " now(), now()) RETURNING id",
                        UUID.class,
                        reporter);
        String attachment =
                "INSERT INTO attachment (incident_id, uploader_id, file_name, file_type, file_size,"
                        + " storage_key, content_hash, uploaded_at)"
                        + " VALUES (?, ?, 'photo.jpg', 'image/jpeg', 10, ?, ?, now())";
        String hash = "a".repeat(64);
        jdbc.update(attachment, incident, reporter, UUID.randomUUID().toString(), hash);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        attachment,
                                        incident,
                                        reporter,
                                        UUID.randomUUID().toString(),
                                        hash))
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
