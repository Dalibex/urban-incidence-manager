package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #274: an incident {@code IN_PROGRESS} can only be resolved by the technician of its active
 * assignment (ADR-0020), so {@code updateUser} refuses to disable that technician or change their
 * role with {@code 422}, listing the incidents, and changes nothing. An incident only {@code
 * ASSIGNED} does not stop it: the operator can still reassign it.
 *
 * <p>Written from the issue, the contract and ADR-0020. The data is inserted with SQL and every
 * change goes through HTTP. The requests commit, as the test of the acceptance and the deactivation
 * at once needs, so the rows of each test are deleted after it; users that acted in the append-only
 * audit trail stay, with random emails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class TechnicianWithIncidentsInProgressIT {

    private static final String USERS = "/api/v1/users/";
    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-11-02T10:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private TransactionTemplate transaction;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdIncidents = new ArrayList<>();

    private UUID administrator;
    private UUID citizen;
    private UUID technician;
    private UUID department;

    @BeforeEach
    void createUsersAndReferenceData() {
        administrator = user(Role.ADMINISTRATOR);
        citizen = user(Role.CITIZEN);
        technician = user(Role.TECHNICIAN);
        department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Street lighting " + UUID.randomUUID());
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        for (UUID incident : createdIncidents) {
            jdbc.update("DELETE FROM notification WHERE incident_id = ?", incident);
            TestHistories.delete(jdbc, "incident_id = ?", incident);
            jdbc.update("DELETE FROM assignment WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update("DELETE FROM department WHERE id = ?", department);
        for (UUID user : createdUsers) {
            jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", user);
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id"
                            + " OR a.target_id = u.id)",
                    user);
        }
    }

    // The bug: the technician was disabled and the incident stayed IN_PROGRESS for good.
    @Test
    void aTechnicianWithAnIncidentInProgressCannotBeDisabled() throws Exception {
        incident(technician, "IN_PROGRESS", "Broken streetlight in Calle Larios");

        var response = update(technician, "{\"enabled\":false}");

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(fields(response)).containsExactly("enabled");
        assertThat(message(response, "enabled"))
                .isEqualTo(
                        "This technician has 1 incident in progress that only they can resolve:"
                                + " “Broken streetlight in Calle Larios”. Deactivate the"
                                + " account once it is resolved.");
        assertUnchanged(technician, Role.TECHNICIAN);
    }

    @Test
    void aTechnicianWithIncidentsInProgressKeepsTheRole() throws Exception {
        incident(technician, "IN_PROGRESS", "Pothole");
        incident(technician, "IN_PROGRESS", "Fallen tree");

        var response = update(technician, "{\"role\":\"MUNICIPAL_OPERATOR\"}");

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(fields(response)).containsExactly("role");
        assertThat(message(response, "role"))
                .isEqualTo(
                        "This technician has 2 incidents in progress that only they can resolve:"
                                + " “Fallen tree”, “Pothole”. Change the role"
                                + " once they are resolved.");
        // Both at once: one error on each field, and still nothing changes.
        var both = update(technician, "{\"role\":\"ANALYST\",\"enabled\":false}");
        assertThat(both.getStatus()).isEqualTo(422);
        assertThat(fields(both)).containsExactlyInAnyOrder("role", "enabled");
        assertUnchanged(technician, Role.TECHNICIAN);
    }

    @Test
    void theMessageListsFiveTitlesAndCountsTheRest() throws Exception {
        for (var title : List.of("A", "B", "C", "D", "E", "F", "G")) {
            incident(technician, "IN_PROGRESS", "Incident " + title);
        }

        var response = update(technician, "{\"enabled\":false}");

        assertThat(message(response, "enabled"))
                .startsWith("This technician has 7 incidents in progress")
                .contains("“Incident E” and 2 more.")
                .doesNotContain("Incident F");
    }

    // Out of scope of the rule: an ASSIGNED incident can be reassigned, and a finished one or one
    // of another technician needs nothing from this one.
    @Test
    void otherIncidentsDoNotStopTheChange() throws Exception {
        var other = user(Role.TECHNICIAN);
        incident(technician, "ASSIGNED", "Not accepted yet");
        incident(technician, "RESOLVED", "Already resolved");
        incident(other, "IN_PROGRESS", "Someone else's");
        var reassigned = incident(technician, "IN_PROGRESS", "No longer theirs");
        jdbc.update("UPDATE assignment SET is_active = false WHERE incident_id = ?", reassigned);

        assertThat(update(technician, "{\"role\":\"ANALYST\"}").getStatus()).isEqualTo(200);
        assertThat(update(technician, "{\"enabled\":false}").getStatus()).isEqualTo(200);
        assertThat(column(technician, "enabled")).isEqualTo("false");
    }

    @Test
    void onceResolvedTheTechnicianCanBeDisabled() throws Exception {
        var incident = incident(technician, "IN_PROGRESS", "Graffiti");
        assertThat(update(technician, "{\"enabled\":false}").getStatus()).isEqualTo(422);

        var resolution =
                mvc.perform(
                                post("/api/v1/incidents/" + incident + "/resolution")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(technician, Role.TECHNICIAN))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"version\":0,\"reason\":\"Painted over.\"}"))
                        .andReturn()
                        .getResponse();
        assertThat(resolution.getStatus()).isEqualTo(200);

        assertThat(update(technician, "{\"enabled\":false}").getStatus()).isEqualTo(200);
    }

    // The issue's limit case: the technician accepts while the administrator disables them. Both
    // wait for the technician's row, and PostgreSQL hands a row lock out in the order it was asked
    // for, so each test fixes which one goes first. One of the two must lose, so the incident never
    // ends IN_PROGRESS with a disabled technician.
    @Test
    void anAcceptanceJustBeforeADeactivationRefusesTheDeactivation() throws Exception {
        var incident = incident(technician, "ASSIGNED", "Blocked drain");

        assertThat(atOnce(incident, true)).containsExactly(200, 422);

        assertThat(statusOf(incident)).isEqualTo("IN_PROGRESS");
        assertUnchanged(technician, Role.TECHNICIAN);
    }

    @Test
    void aDeactivationJustBeforeAnAcceptanceRefusesTheAcceptance() throws Exception {
        var incident = incident(technician, "ASSIGNED", "Blocked drain");

        assertThat(atOnce(incident, false)).containsExactly(409, 200);

        assertThat(statusOf(incident)).isEqualTo("ASSIGNED");
        assertThat(column(technician, "enabled")).isEqualTo("false");
    }

    /**
     * Sends the acceptance of the incident and the deactivation of the technician while a
     * transaction of the test holds the technician's row, the first one asked for first, then lets
     * them go.
     *
     * @return the status of the acceptance and of the deactivation
     */
    private List<Integer> atOnce(UUID incident, boolean acceptanceFirst) throws Exception {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder =
                CompletableFuture.runAsync(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.queryForList(
                                                    "SELECT id FROM users WHERE id = ? FOR UPDATE",
                                                    technician);
                                            holding.countDown();
                                            await(release);
                                        }));
        await(holding);

        var acceptance =
                post("/api/v1/incidents/" + incident + "/acceptance")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                tokens.bearer(technician, Role.TECHNICIAN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}");
        var deactivation =
                patch(USERS + technician)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                tokens.bearer(administrator, Role.ADMINISTRATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}");
        var first =
                CompletableFuture.supplyAsync(
                        () -> status(acceptanceFirst ? acceptance : deactivation));
        waitUntilBlocked(1);
        var second =
                CompletableFuture.supplyAsync(
                        () -> status(acceptanceFirst ? deactivation : acceptance));
        waitUntilBlocked(2);
        release.countDown();
        holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        var firstStatus = first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        var secondStatus = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        return acceptanceFirst
                ? List.of(firstStatus, secondStatus)
                : List.of(secondStatus, firstStatus);
    }

    // --- helpers ---

    /** An incident in {@code status} whose active assignment is {@code assignee}'s. */
    private UUID incident(UUID assignee, String status, String title) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at) VALUES (?, ?, 'Seen this morning.', ?,"
                                + " ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        title,
                        status,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(ASSIGNED_AT));
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at,"
                        + " is_active) VALUES (?, ?, ?, ?, true)",
                incident,
                department,
                assignee,
                Timestamp.from(ASSIGNED_AT));
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, visible_to_citizen) VALUES (?, ?, ?, NULL, 'REPORTED', true)",
                incident,
                citizen,
                Timestamp.from(REPORTED_AT));
        return incident;
    }

    private MockHttpServletResponse update(UUID user, String body) throws Exception {
        return mvc.perform(
                        patch(USERS + user)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(administrator, Role.ADMINISTRATOR))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn()
                .getResponse();
    }

    private int status(MockHttpServletRequestBuilder request) {
        try {
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fields(MockHttpServletResponse response) throws Exception {
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
                .isEqualTo("VALIDATION_FAILED");
        return JsonPath.read(response.getContentAsString(), "$.errors[*].field");
    }

    private static String message(MockHttpServletResponse response, String field) throws Exception {
        List<Map<String, String>> errors =
                JsonPath.read(response.getContentAsString(), "$.errors[*]");
        return errors.stream()
                .filter(error -> field.equals(error.get("field")))
                .map(error -> error.get("message"))
                .findFirst()
                .orElseThrow();
    }

    /** Still an enabled account of the role, with no audit entry about it. */
    private void assertUnchanged(UUID user, Role role) {
        assertThat(column(user, "role")).isEqualTo(role.name());
        assertThat(column(user, "enabled")).isEqualTo("true");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_log WHERE target_id = ?",
                                Integer.class,
                                user))
                .isZero();
    }

    private String statusOf(UUID incident) {
        return jdbc.queryForObject(
                "SELECT status FROM incident WHERE id = ?", String.class, incident);
    }

    private String column(UUID user, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + "::text FROM users WHERE id = ?", String.class, user);
    }

    /** Waits until this many sessions of the database wait for a row lock. */
    private void waitUntilBlocked(int sessions) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var blocked =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
                            Integer.class);
            if (blocked != null && blocked >= sessions) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(sessions + " requests never waited for the lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private UUID user(Role role) {
        UUID user =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES (?, ?, '{bcrypt}x', ?) RETURNING id",
                        UUID.class,
                        "Test " + role,
                        "t" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                        role.name());
        createdUsers.add(user);
        return user;
    }
}
