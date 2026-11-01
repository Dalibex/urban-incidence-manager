package es.uma.urbanpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
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
                    Map.entry("users", new DatasetRows("d0000000-", 7)),
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

    private record Entry(
            Instant instant,
            IncidentStatus previous,
            IncidentStatus next,
            UUID actor,
            Role actorRole,
            String reason) {}

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
        var incidents =
                jdbc.queryForList(
                        "SELECT id, reporter_id, status, category_id, priority, reported_at,"
                                + " updated_at, closed_at FROM incident");

        for (var incident : incidents) {
            var id = (UUID) incident.get("id");
            var history = history(id);
            var first = history.getFirst();
            assertThat(first.previous()).as("%s first entry", id).isNull();
            assertThat(first.next()).isEqualTo(IncidentStatus.REPORTED);
            assertThat(first.actor()).isEqualTo(incident.get("reporter_id"));
            assertThat(first.actorRole()).isEqualTo(Role.CITIZEN);
            assertThat(first.instant()).isEqualTo(instant(incident.get("reported_at")));

            for (int i = 1; i < history.size(); i++) {
                var entry = history.get(i);
                assertThat(entry.previous())
                        .as("%s entry %d", id, i)
                        .isEqualTo(history.get(i - 1).next());
                var transition = transitionMaking(entry);
                assertThat(transition).as("%s entry %d", id, i).isNotNull();
                assertMayPerform(id, entry, transition);
                if (transition.requiresReason()) {
                    assertThat(entry.reason()).as("%s entry %d reason", id, i).isNotBlank();
                }
                if (transition == IncidentTransition.VALIDATION) {
                    assertThat(incident.get("category_id")).as("%s category", id).isNotNull();
                    assertThat(incident.get("priority")).as("%s priority", id).isNotNull();
                }
            }

            var last = history.getLast();
            assertThat(incident.get("status")).as("%s status", id).isEqualTo(last.next().name());
            assertThat(instant(incident.get("updated_at"))).isAfterOrEqualTo(last.instant());
            if (last.next() == IncidentStatus.CLOSED) {
                assertThat(instant(incident.get("closed_at"))).isEqualTo(last.instant());
            } else {
                assertThat(incident.get("closed_at")).as("%s closed_at", id).isNull();
            }
            assertAssignmentsFollowTheHistory(id, history);
        }
    }

    @Test
    void everyDevelopmentUserSignsInWithTheDocumentedPassword() throws Exception {
        var emails =
                jdbc.queryForList(
                        "SELECT email FROM users WHERE enabled AND id::text LIKE 'd0000000-%'",
                        String.class);
        assertThat(emails).hasSize(ROWS.get("users").count());

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

    /** The transition of the table that goes from the entry's previous status to its new one. */
    private static IncidentTransition transitionMaking(Entry entry) {
        return Arrays.stream(IncidentTransition.values())
                .filter(
                        transition ->
                                transition.startsFrom(entry.previous())
                                        && transition.target() == entry.next())
                .findFirst()
                .orElse(null);
    }

    /** The table says who may make the transition (ADR-0020, "Who"). */
    private void assertMayPerform(UUID incident, Entry entry, IncidentTransition transition) {
        switch (transition.performer()) {
            case OPERATOR ->
                    assertThat(entry.actorRole())
                            .as("%s %s", incident, transition)
                            .isIn(Role.MUNICIPAL_OPERATOR, Role.ADMINISTRATOR);
            case ASSIGNED_TECHNICIAN -> {
                var assignee =
                        jdbc.queryForList(
                                "SELECT technician_id FROM assignment WHERE incident_id = ?"
                                        + " AND assigned_at <= ? ORDER BY assigned_at DESC LIMIT 1",
                                UUID.class,
                                incident,
                                Timestamp.from(entry.instant()));
                assertThat(assignee)
                        .as("%s %s", incident, transition)
                        .containsExactly(entry.actor());
            }
        }
    }

    /** One assignment per ASSIGNMENT entry, and only the last one is active. */
    private void assertAssignmentsFollowTheHistory(UUID incident, List<Entry> history) {
        var assignedAt =
                history.stream()
                        .filter(entry -> entry.next() == IncidentStatus.ASSIGNED)
                        .map(Entry::instant)
                        .toList();
        var assignments =
                jdbc.queryForList(
                        "SELECT assigned_at, is_active FROM assignment WHERE incident_id = ?"
                                + " ORDER BY assigned_at",
                        incident);

        assertThat(assignments.stream().map(row -> instant(row.get("assigned_at"))).toList())
                .as("%s assignments", incident)
                .isEqualTo(assignedAt);
        assertThat(assignments.stream().map(row -> (Boolean) row.get("is_active")).toList())
                .as("%s active assignment", incident)
                .isEqualTo(assignedAt.stream().map(at -> at.equals(assignedAt.getLast())).toList());
    }

    private List<Entry> history(UUID incident) {
        return jdbc.query(
                "SELECT s.instant, s.previous_status, s.new_status, s.actor_id, u.role, s.reason"
                        + " FROM status_change s JOIN users u ON u.id = s.actor_id"
                        + " WHERE s.incident_id = ? ORDER BY s.instant, s.id",
                DevelopmentDatasetIT::entry,
                incident);
    }

    private static Entry entry(ResultSet row, int rowNumber) throws SQLException {
        var previous = row.getString("previous_status");
        return new Entry(
                row.getTimestamp("instant").toInstant(),
                previous == null ? null : IncidentStatus.valueOf(previous),
                IncidentStatus.valueOf(row.getString("new_status")),
                row.getObject("actor_id", UUID.class),
                Role.valueOf(row.getString("role")),
                row.getString("reason"));
    }

    private int datasetRows(String table, String idPrefix) {
        return count("SELECT count(*) FROM " + table + " WHERE id::text LIKE '" + idPrefix + "%'");
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static Instant instant(Object timestamp) {
        return switch (timestamp) {
            case Timestamp sql -> sql.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalArgumentException("Not a timestamp: " + timestamp);
        };
    }
}
