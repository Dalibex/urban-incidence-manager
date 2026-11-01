package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * The demo dataset of the {@code demo} profile (#54, docs/demo.md). Like {@link
 * DevelopmentDatasetIT}, the profile gives this class its own application context and PostGIS
 * container, and these tests catch a schema migration that breaks the dataset.
 */
@SpringBootTest(properties = "DEMO_PASSWORD=" + DemoDatasetIT.PASSWORD)
@ActiveProfiles("demo")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoDatasetIT {

    static final String PASSWORD = "demo-password-for-the-tests";

    private static final String DATASET = "db/demo-data/R__demo_data.sql";
    private static final String PASSWORD_PLACEHOLDER = "${demo_password}";

    /** Rows of the dataset per table, counted by the prefix of their fixed ids. */
    private static final Map<String, DatasetRows> ROWS =
            Map.ofEntries(
                    Map.entry("users", new DatasetRows("e0000000-", 5)),
                    Map.entry("incident", new DatasetRows("e1000000-", 15)),
                    Map.entry("status_change", new DatasetRows("e2000000-", 48)),
                    Map.entry("location", new DatasetRows("e3000000-", 15)),
                    Map.entry("assignment", new DatasetRows("e4000000-", 8)));

    /** Tables the story does not use; the dataset empties them like the incident tables. */
    private static final List<String> EMPTY_TABLES =
            List.of(
                    "comment",
                    "attachment",
                    "urban_context",
                    "external_observation",
                    "incident_asset",
                    "notification",
                    "audit_log");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;

    private record DatasetRows(String idPrefix, int count) {}

    @Test
    void fillsTheTablesOfTheStoryAndNoOthers() {
        assertDatasetRows();
        for (var table : EMPTY_TABLES) {
            assertThat(count("SELECT count(*) FROM " + table)).as(table).isZero();
        }
    }

    @Test
    void hasOneUserPerRole() {
        var roles =
                jdbc.queryForList(
                        "SELECT role FROM users WHERE enabled AND id::text LIKE 'e0000000-%'",
                        String.class);

        assertThat(roles)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Role.values()).map(Enum::name).toList());
    }

    @Test
    void coversEveryIncidentStatus() {
        var statuses = jdbc.queryForList("SELECT DISTINCT status FROM incident", String.class);

        assertThat(statuses)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(IncidentStatus.values()).map(Enum::name).toList());
    }

    @Test
    void placesEveryIncidentInMalaga() {
        var outside =
                count(
                        "SELECT count(*) FROM location WHERE NOT ST_Intersects(coordinates,"
                                + " ST_MakeEnvelope(-4.60, 36.65, -4.30, 36.80, 4326)::geography)");

        assertThat(outside).isZero();
    }

    @Test
    void leavesTheReferenceDataUnchanged() {
        assertThat(count("SELECT count(*) FROM category WHERE is_active")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM department WHERE is_active")).isEqualTo(5);
    }

    @Test
    void everyHistoryIsMadeOfTransitionsOfTheTable() {
        new DatasetHistories(jdbc).assertEveryHistoryFollowsTheTransitionTable();
    }

    @Test
    void runningItAgainCreatesNoDuplicates() throws IOException {
        var script = datasetScript().replace(PASSWORD_PLACEHOLDER, PASSWORD);

        new ResourceDatabasePopulator(
                        new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);

        assertDatasetRows();
        assertThat(count("SELECT count(*) FROM incident")).isEqualTo(ROWS.get("incident").count());
    }

    @Test
    void writesNoPasswordOrHashInTheScript() throws IOException {
        var script = datasetScript();

        assertThat(script).contains(PASSWORD_PLACEHOLDER).doesNotContain("$2a$", "{bcrypt}$");
    }

    @Test
    void everyDemoUserSignsInWithThePasswordFromTheEnvironment() throws Exception {
        var emails = demoEmails();
        assertThat(emails).hasSize(ROWS.get("users").count());

        for (int i = 0; i < emails.size(); i++) {
            signIn("10.54.0." + (i + 1), emails.get(i), PASSWORD, status().isCreated());
        }
    }

    @Test
    void theDevelopmentPasswordDoesNotOpenADemoAccount() throws Exception {
        signIn("10.54.1.1", demoEmails().getFirst(), "urbanpulse-dev", status().isUnauthorized());
    }

    private void signIn(String clientAddress, String email, String password, ResultMatcher expected)
            throws Exception {
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
                                                .formatted(email, password)))
                .andExpect(expected);
    }

    private List<String> demoEmails() {
        return jdbc.queryForList(
                "SELECT email FROM users WHERE enabled AND id::text LIKE 'e0000000-%' ORDER BY id",
                String.class);
    }

    private static String datasetScript() throws IOException {
        return new ClassPathResource(DATASET).getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertDatasetRows() {
        ROWS.forEach(
                (table, rows) ->
                        assertThat(
                                        count(
                                                "SELECT count(*) FROM "
                                                        + table
                                                        + " WHERE id::text LIKE '"
                                                        + rows.idPrefix()
                                                        + "%'"))
                                .as(table)
                                .isEqualTo(rows.count()));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
