package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acceptance tests of #75 (T2 to T11) for the operator's actions on a resolved incident of #52:
 * {@code createIncidentClosure} and {@code createIncidentReopening}.
 *
 * <p>They are written from the issues, the contract ({@code docs/api/openapi.yml}), {@code
 * docs/security.md} and ADR-0020, not from the implementation: the data is inserted with SQL on the
 * Flyway schema, every action goes through HTTP with the {@code version} read from the detail, and
 * the effect is checked in the database. Every response is also validated against the contract
 * ({@link ApiContract}).
 *
 * <p>They do not run inside a rolled-back transaction: the version check and "nothing changes" are
 * only meaningful on what each request really committed. The rows of each test are deleted after
 * it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class IncidentClosureApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String CLOSURE = "closure";
    private static final String[] ACTIONS = {CLOSURE};

    /** Every status but {@code RESOLVED}, the only one these actions start from (ADR-0020). */
    private static final String[] OTHER_STATUSES = {
        "REPORTED", "VALIDATED", "REJECTED", "ASSIGNED", "IN_PROGRESS", "REOPENED", "CLOSED"
    };

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-11-03T12:00:00Z");
    private static final String CLOSURE_NOTE = "Checked on site: the lamp works.";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdIncidents = new ArrayList<>();

    private UUID citizen;
    private UUID operator;
    private UUID otherOperator;
    private UUID administrator;
    private UUID technician;
    private UUID analyst;
    private UUID category;
    private UUID department;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        otherOperator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        analyst = user(Role.ANALYST);
        category =
                jdbc.queryForObject(
                        "INSERT INTO category (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Lighting " + UUID.randomUUID());
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
            jdbc.update("DELETE FROM status_change WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM assignment WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", category);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
        // The audit trail is append-only and references its actor: a user who acted in it stays,
        // with a random email, so it cannot clash with another test.
        for (UUID user : createdUsers) {
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                    user);
        }
    }

    // T2: an operator closes a resolved incident: RESOLVED to CLOSED, with closedAt (ADR-0020).
    @Test
    void anOperatorClosesAResolvedIncident() throws Exception {
        UUID incident = inStatus("RESOLVED");
        int version = versionOf(incident);
        Instant before = Instant.now();

        String answer =
                act(operator, incident, CLOSURE, body(version, null))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(incident.toString()))
                        .andExpect(jsonPath("$.status").value("CLOSED"))
                        .andExpect(jsonPath("$.version").value(version + 1))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(Instant.parse(JsonPath.read(answer, "$.closedAt"))).isAfterOrEqualTo(before);
        assertThat(statusOf(incident)).isEqualTo("CLOSED");
        assertThat(closedAtOf(incident)).isAfterOrEqualTo(before);
        assertThat(historyCount(incident)).isEqualTo(3);
        assertThat(lastHistoryEntry(incident))
                .containsEntry("previous_status", "RESOLVED")
                .containsEntry("new_status", "CLOSED")
                .containsEntry("actor_id", operator)
                .containsEntry("reason", null)
                .containsEntry("visible_to_citizen", true);
    }

    // T2: the citizen sees the incident CLOSED, with the date it was closed.
    @Test
    void theCitizenSeesTheIncidentClosed() throws Exception {
        UUID incident = inStatus("RESOLVED");

        act(operator, incident, CLOSURE, body(versionOf(incident), CLOSURE_NOTE))
                .andExpect(status().isOk());

        mvc.perform(as(citizen, get(INCIDENTS + "/" + incident)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        String history = citizenHistoryText(incident);
        List<Map<String, Object>> entries = JsonPath.read(history, "$");
        assertThat(entries)
                .extracting(entry -> entry.get("newStatus"))
                .containsExactly("REPORTED", "RESOLVED", "CLOSED");
        assertThat(entries).extracting(entry -> entry.get("reason")).containsOnlyNulls();
        assertThat(history).doesNotContain(CLOSURE_NOTE).doesNotContain(operator.toString());
    }

    // T2: an administrator closes too, with an optional note; a blank one is refused instead of
    // stored.
    @Test
    void theClosureNoteIsOptionalButNeverBlank() throws Exception {
        UUID withNote = inStatus("RESOLVED");
        UUID blankNote = inStatus("RESOLVED");

        act(administrator, withNote, CLOSURE, body(versionOf(withNote), CLOSURE_NOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        for (String note : new String[] {"", "   ", "\n"}) {
            expectUnprocessable(act(operator, blankNote, CLOSURE, body(0, note)), "reason");
        }

        assertThat(lastHistoryEntry(withNote))
                .containsEntry("actor_id", administrator)
                .containsEntry("reason", CLOSURE_NOTE);
        assertUnchanged(blankNote, "RESOLVED", 0, 2);
        assertThat(closedAtOf(blankNote)).isNull();
    }

    // T5 and T11: only operators and administrators close or reopen (docs/security.md); the
    // technician, even the assigned one, the citizen, even the reporter, and an analyst get 403.
    @Test
    void otherRolesAreForbidden() throws Exception {
        UUID incident = inStatus("RESOLVED");

        for (UUID caller : new UUID[] {technician, citizen, analyst}) {
            for (String action : ACTIONS) {
                expectForbidden(act(caller, incident, action, validBody(action, 0)));
            }
        }

        assertUnchanged(incident, "RESOLVED", 0, 2);
    }

    // T7: a version read before someone else's change is refused on every action, with no history
    // entry.
    @Test
    void aStaleVersionIsAConcurrentModificationOnEveryAction() throws Exception {
        UUID incident = inStatus("RESOLVED");
        jdbc.update("UPDATE incident SET version = version + 1 WHERE id = ?", incident);

        for (String action : ACTIONS) {
            act(operator, incident, action, validBody(action, 0))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        }

        assertUnchanged(incident, "RESOLVED", 1, 2);
        assertThat(closedAtOf(incident)).isNull();
    }

    // T8: an incident that is not RESOLVED cannot be closed or reopened: 409, nothing changes.
    @Test
    void anIncidentThatIsNotResolvedIsAConflict() throws Exception {
        for (String status : OTHER_STATUSES) {
            UUID incident = inStatus(status);
            for (String action : ACTIONS) {
                act(operator, incident, action, validBody(action, 0))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
            }
            assertUnchanged(incident, status, 0, 2);
        }
    }

    // T9: closing twice, as a double click or two operators on the same version: the second one is
    // refused, the history has one closure and closedAt keeps the first instant.
    // IncidentLifecycleServiceIT covers two requests that really overlap.
    @Test
    void closingTwiceLeavesOneClosure() throws Exception {
        UUID incident = inStatus("RESOLVED");
        int version = versionOf(incident);

        act(operator, incident, CLOSURE, body(version, null)).andExpect(status().isOk());
        Instant closedAt = closedAtOf(incident);
        act(otherOperator, incident, CLOSURE, body(version, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        // With the new version it is no longer RESOLVED: CLOSED is final.
        act(otherOperator, incident, CLOSURE, body(version + 1, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        assertUnchanged(incident, "CLOSED", version + 1, 3);
        assertThat(closedAtOf(incident)).isEqualTo(closedAt);
        assertThat(lastHistoryEntry(incident)).containsEntry("actor_id", operator);
    }

    // ADR-0020, "Errors": a missing or negative version is invalid data, so it is a 422 only after
    // the role (403), the access (404) and the transition (409 INVALID_TRANSITION).
    @Test
    void anInvalidVersionIsCheckedAfterTheRoleTheAccessAndTheTransition() throws Exception {
        UUID resolved = inStatus("RESOLVED");
        UUID closed = inStatus("CLOSED");

        for (String action : ACTIONS) {
            expectForbidden(act(technician, resolved, action, "{}"));
            expectNotFound(act(operator, UUID.randomUUID(), action, "{}"));
            for (String body : new String[] {"{}", "{\"version\": -1}"}) {
                expectUnprocessable(act(operator, resolved, action, body), "version");
                act(operator, closed, action, body)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
            }
        }

        assertUnchanged(resolved, "RESOLVED", 0, 2);
        assertUnchanged(closed, "CLOSED", 0, 2);
    }

    // T11: an incident that does not exist is a 404; without a token it is a 401.
    @Test
    void unknownIncidentsAndRequestsWithoutAToken() throws Exception {
        UUID incident = inStatus("RESOLVED");

        for (String action : ACTIONS) {
            expectNotFound(act(operator, UUID.randomUUID(), action, validBody(action, 0)));
            mvc.perform(
                            post(actionPath(incident, action))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(validBody(action, 0)))
                    .andExpect(status().isUnauthorized());
        }

        assertUnchanged(incident, "RESOLVED", 0, 2);
    }

    /**
     * An incident in {@code status}, classified and with the technician's active assignment, with
     * two history entries: the report and the last change, which leaves it in {@code status}. Only
     * the status and the count matter to these tests, not the steps in between.
     */
    private UUID inStatus(String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at, updated_at,"
                                + " closed_at) VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?,"
                                + " 'HIGH', 'Dark crossing near a school', 'MANUAL', ?, ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        category,
                        status,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(RESOLVED_AT),
                        "CLOSED".equals(status) ? Timestamp.from(RESOLVED_AT) : null);
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?, true)",
                incident,
                department,
                technician,
                Timestamp.from(REPORTED_AT.plusSeconds(3600)));
        history(incident, citizen, REPORTED_AT, null, "REPORTED");
        history(incident, technician, RESOLVED_AT, "IN_PROGRESS", status);
        return incident;
    }

    private void history(UUID incident, UUID actor, Instant instant, String previous, String next) {
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, visible_to_citizen) VALUES (?, ?, ?, ?, ?, true)",
                incident,
                actor,
                Timestamp.from(instant),
                previous,
                next);
    }

    private UUID user(Role role) {
        UUID user =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                        UUID.class,
                        "Test " + role,
                        role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                        role.name());
        createdUsers.add(user);
        return user;
    }

    /** The version a staff client reads from the detail before acting (ADR-0020). */
    private int versionOf(UUID incident) throws Exception {
        String detail =
                mvc.perform(as(operator, get(INCIDENTS + "/" + incident)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(detail, "$.version");
    }

    /** The history as the reporter reads it. */
    private String citizenHistoryText(UUID incident) throws Exception {
        return mvc.perform(as(citizen, get(INCIDENTS + "/" + incident + "/status-changes")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private ResultActions act(UUID caller, UUID incident, String action, String body)
            throws Exception {
        return mvc.perform(
                as(
                        caller,
                        post(actionPath(incident, action))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)));
    }

    private static String actionPath(UUID incident, String action) {
        return INCIDENTS + "/" + incident + "/" + action;
    }

    /** A body the action accepts on a resolved incident read at {@code version}. */
    private static String validBody(String action, int version) {
        return switch (action) {
            case CLOSURE -> body(version, null);
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** The request body of an action; a null reason is left out of it. */
    private static String body(int version, String reason) {
        if (reason == null) {
            return "{\"version\": " + version + "}";
        }
        String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"version\": " + version + ", \"reason\": \"" + escaped + "\"}";
    }

    private MockHttpServletRequestBuilder as(UUID userId, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId, roleOf(userId)));
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private String statusOf(UUID incident) {
        return jdbc.queryForObject(
                "SELECT status FROM incident WHERE id = ?", String.class, incident);
    }

    private Instant closedAtOf(UUID incident) {
        Timestamp closedAt =
                jdbc.queryForObject(
                        "SELECT closed_at FROM incident WHERE id = ?", Timestamp.class, incident);
        return closedAt == null ? null : closedAt.toInstant();
    }

    private int historyCount(UUID incident) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM status_change WHERE incident_id = ?",
                Integer.class,
                incident);
    }

    private Map<String, Object> lastHistoryEntry(UUID incident) {
        return jdbc.queryForMap(
                "SELECT * FROM status_change WHERE incident_id = ? ORDER BY instant DESC LIMIT 1",
                incident);
    }

    /** The status, the version and the history are exactly as before the refused requests. */
    private void assertUnchanged(UUID incident, String status, int version, int historyEntries) {
        assertThat(statusOf(incident)).isEqualTo(status);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM incident WHERE id = ?",
                                Integer.class,
                                incident))
                .isEqualTo(version);
        assertThat(historyCount(incident)).isEqualTo(historyEntries);
    }

    private static void expectUnprocessable(ResultActions result, String... fields)
            throws Exception {
        String body =
                result.andExpect(status().isUnprocessableContent())
                        .andExpect(
                                content()
                                        .contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<String> invalid = JsonPath.read(body, "$.errors[*].field");
        assertThat(invalid).containsExactlyInAnyOrder(fields);
    }

    private static void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
