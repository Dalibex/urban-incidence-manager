package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The development dataset of the {@code dev} profile (#83, docs/development.md). The profile gives
 * this class its own application context, and with it its own PostGIS container, so the dataset
 * never reaches the other integration tests. CI never starts the application with the profile, so
 * these tests are also what catches a schema migration that breaks the dataset.
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DevelopmentDatasetIT {

    private static final String DATASET = "db/dev-data/R__dev_data.sql";
    private static final String PASSWORD = "urbanpulse-dev";

    /** Rows of the dataset per table, counted by the prefix of their fixed ids. */
    private static final Map<String, DatasetRows> ROWS =
            Map.ofEntries(
                    Map.entry("users", new DatasetRows("d0000000-", 8)),
                    Map.entry("incident", new DatasetRows("d1000000-", 10)),
                    Map.entry("status_change", new DatasetRows("d2000000-", 35)),
                    Map.entry("location", new DatasetRows("d3000000-", 10)),
                    Map.entry("assignment", new DatasetRows("d4000000-", 7)),
                    Map.entry("comment", new DatasetRows("d5000000-", 3)),
                    Map.entry("attachment", new DatasetRows("d6000000-", 3)),
                    Map.entry("urban_context", new DatasetRows("d7000000-", 6)),
                    Map.entry("external_observation", new DatasetRows("d8000000-", 4)),
                    Map.entry("urban_asset", new DatasetRows("d9000000-", 4)),
                    Map.entry("incident_asset", new DatasetRows("da000000-", 4)),
                    Map.entry("notification", new DatasetRows("db000000-", 4)));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;

    private record DatasetRows(String idPrefix, int count) {}

    @Test
    void fillsEveryTableOfTheDatasetAndNotTheOthers() {
        ROWS.forEach(
                (table, rows) ->
                        assertThat(datasetRows(table, rows.idPrefix()))
                                .as(table)
                                .isEqualTo(rows.count()));
        assertThat(count("SELECT count(*) FROM audit_log")).isZero();
        assertThat(count("SELECT count(*) FROM data_source_config")).isZero();
    }

    @Test
    void leavesTheReferenceDataUnchanged() {
        assertThat(count("SELECT count(*) FROM category WHERE is_active")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM category")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM department WHERE is_active")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM department")).isEqualTo(5);
    }

    @Test
    void coversEveryIncidentStatus() {
        var statuses = jdbc.queryForList("SELECT DISTINCT status FROM incident", String.class);

        assertThat(statuses)
                .containsExactlyInAnyOrderElementsOf(
                        List.of(IncidentStatus.values()).stream().map(Enum::name).toList());
    }

    @Test
    void runningItAgainKeepsTheUsersAndReloadsTheIncidentData() {
        var userSignedUpByHand = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash, role) VALUES (?, 'Hand', ?, 'x', 'CITIZEN')",
                userSignedUpByHand,
                userSignedUpByHand + "@example.com");
        jdbc.update(
                "INSERT INTO incident (reporter_id, title, description, status, reported_at, updated_at)"
                        + " VALUES (?, 'By hand', 'Created while testing', 'REPORTED', now(), now())",
                userSignedUpByHand);

        new ResourceDatabasePopulator(new ClassPathResource(DATASET)).execute(dataSource);

        ROWS.forEach(
                (table, rows) ->
                        assertThat(datasetRows(table, rows.idPrefix()))
                                .as(table)
                                .isEqualTo(rows.count()));
        assertThat(count("SELECT count(*) FROM incident")).isEqualTo(ROWS.get("incident").count());
        assertThat(count("SELECT count(*) FROM users WHERE id = '" + userSignedUpByHand + "'"))
                .isOne();
        jdbc.update("DELETE FROM users WHERE id = ?", userSignedUpByHand);
    }

    @Test
    void everyHistoryIsMadeOfTransitionsOfTheTable() {
        new DatasetHistories(jdbc).assertEveryHistoryFollowsTheTransitionTable();
    }

    @Test
    void everyDevelopmentUserSignsInWithTheDocumentedPassword() throws Exception {
        var emails =
                jdbc.queryForList(
                        "SELECT email FROM users WHERE enabled AND NOT password_change_required"
                                + " AND id::text LIKE 'd0000000-%'",
                        String.class);
        // dev.newstaff must change the password first (#165): every other user signs in.
        assertThat(emails).hasSize(ROWS.get("users").count() - 1);

        for (int i = 0; i < emails.size(); i++) {
            var clientAddress = "10.83.0." + (i + 1);
            mvc.perform(
                            post("/api/v1/sessions")
                                    .with(
                                            request -> {
                                                request.setRemoteAddr(clientAddress);
                                                return request;
                                            })
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"email": "%s", "password": "%s"}
                                            """
                                                    .formatted(emails.get(i), PASSWORD)))
                    .andExpect(status().isCreated());
        }
    }

    private int datasetRows(String table, String idPrefix) {
        return count("SELECT count(*) FROM " + table + " WHERE id::text LIKE '" + idPrefix + "%'");
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
