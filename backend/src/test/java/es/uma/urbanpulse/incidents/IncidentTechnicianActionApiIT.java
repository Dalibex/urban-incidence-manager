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
 * Acceptance tests of #74 (T4, T5 and T7 to T13) for the technician's actions of #51 and #186:
 * {@code createIncidentAcceptance} and {@code createIncidentResolution}.
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
class IncidentTechnicianActionApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String ACCEPTANCE = "acceptance";
    private static final String RESOLUTION = "resolution";
    private static final String[] ACTIONS = {ACCEPTANCE, RESOLUTION};

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-11-02T10:00:00Z");
    private static final String RESOLUTION_NOTES = "Replaced the lamp; internal ticket 4411.";
    private static final String ACCEPTANCE_NOTE = "Going tonight with the lift truck.";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdIncidents = new ArrayList<>();

    private UUID citizen;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID analyst;
    private UUID category;
    private UUID department;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        otherTechnician = user(Role.TECHNICIAN);
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

    // T4: the assigned technician accepts: ASSIGNED to IN_PROGRESS (ADR-0020).
    @Test
    void theAssignedTechnicianAcceptsTheIncident() throws Exception {
        UUID incident = assignedTo(technician, "ASSIGNED");
        int version = versionOf(incident);

        act(technician, incident, ACCEPTANCE, body(version, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incident.toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(version + 1));

        assertThat(statusOf(incident)).isEqualTo("IN_PROGRESS");
        assertThat(historyCount(incident)).isEqualTo(3);
        assertThat(lastHistoryEntry(incident))
                .containsEntry("previous_status", "ASSIGNED")
                .containsEntry("new_status", "IN_PROGRESS")
                .containsEntry("actor_id", technician)
                .containsEntry("visible_to_citizen", true);
    }

    // T4: acceptance takes an optional note; a blank one is refused instead of stored.
    @Test
    void theAcceptanceNoteIsOptionalButNeverBlank() throws Exception {
        UUID withNote = assignedTo(technician, "ASSIGNED");
        UUID blankNote = assignedTo(technician, "ASSIGNED");

        act(technician, withNote, ACCEPTANCE, body(versionOf(withNote), ACCEPTANCE_NOTE))
                .andExpect(status().isOk());
        expectUnprocessable(act(technician, blankNote, ACCEPTANCE, body(0, "   ")), "reason");

        assertThat(lastHistoryEntry(withNote)).containsEntry("reason", ACCEPTANCE_NOTE);
        assertUnchanged(blankNote, "ASSIGNED", 0, 2);
    }

    // T4: the assigned technician resolves with the resolution notes: IN_PROGRESS to RESOLVED.
    @Test
    void theAssignedTechnicianResolvesWithNotes() throws Exception {
        UUID incident = assignedTo(technician, "IN_PROGRESS");
        int version = versionOf(incident);

        act(technician, incident, RESOLUTION, body(version, RESOLUTION_NOTES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.version").value(version + 1));

        assertThat(statusOf(incident)).isEqualTo("RESOLVED");
        assertThat(lastHistoryEntry(incident))
                .containsEntry("previous_status", "IN_PROGRESS")
                .containsEntry("new_status", "RESOLVED")
                .containsEntry("actor_id", technician)
                .containsEntry("reason", RESOLUTION_NOTES);
    }

    // T4: resolving without notes, or with blank ones, is 422 and changes nothing.
    @Test
    void resolvingWithoutNotesIsUnprocessableAndChangesNothing() throws Exception {
        UUID incident = assignedTo(technician, "IN_PROGRESS");

        for (String notes : new String[] {null, "", " \n "}) {
            expectUnprocessable(act(technician, incident, RESOLUTION, body(0, notes)), "reason");
        }

        assertUnchanged(incident, "IN_PROGRESS", 0, 2);
    }

    // T4: the notes have the limit of the contract (maxLength 2000).
    @Test
    void resolutionNotesOverTheLimitAreUnprocessable() throws Exception {
        UUID incident = assignedTo(technician, "IN_PROGRESS");

        expectUnprocessable(
                act(technician, incident, RESOLUTION, body(0, "a".repeat(2001))), "reason");
        assertUnchanged(incident, "IN_PROGRESS", 0, 2);

        act(technician, incident, RESOLUTION, body(0, "a".repeat(2000))).andExpect(status().isOk());
    }

    // T5: a technician who is not the assigned one gets 404, as if the incident did not exist.
    @Test
    void anotherTechnicianGetsNotFound() throws Exception {
        UUID toAccept = assignedTo(technician, "ASSIGNED");
        UUID toResolve = assignedTo(technician, "IN_PROGRESS");

        expectNotFound(act(otherTechnician, toAccept, ACCEPTANCE, body(0, null)));
        expectNotFound(act(otherTechnician, toResolve, RESOLUTION, body(0, RESOLUTION_NOTES)));

        assertUnchanged(toAccept, "ASSIGNED", 0, 2);
        assertUnchanged(toResolve, "IN_PROGRESS", 0, 2);
    }

    // T7: a version read before someone else's change is refused on every action.
    @Test
    void aStaleVersionIsAConcurrentModificationOnEveryAction() throws Exception {
        UUID toAccept = assignedTo(technician, "ASSIGNED");
        UUID toResolve = assignedTo(technician, "IN_PROGRESS");
        for (UUID incident : List.of(toAccept, toResolve)) {
            jdbc.update("UPDATE incident SET version = version + 1 WHERE id = ?", incident);
        }

        act(technician, toAccept, ACCEPTANCE, body(0, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        act(technician, toResolve, RESOLUTION, body(0, RESOLUTION_NOTES))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertUnchanged(toAccept, "ASSIGNED", 1, 2);
        assertUnchanged(toResolve, "IN_PROGRESS", 1, 2);
    }

    // T8: a double click sends the same version twice; the second request is refused.
    @Test
    void acceptingTwiceLeavesOneHistoryEntry() throws Exception {
        UUID incident = assignedTo(technician, "ASSIGNED");
        int version = versionOf(incident);

        act(technician, incident, ACCEPTANCE, body(version, null)).andExpect(status().isOk());
        act(technician, incident, ACCEPTANCE, body(version, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        // With the new version, accepting again is no longer a valid transition.
        act(technician, incident, ACCEPTANCE, body(versionOf(incident), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        assertUnchanged(incident, "IN_PROGRESS", version + 1, 3);
    }

    // T9: accept an IN_PROGRESS incident or resolve an ASSIGNED one: 409, nothing changes.
    @Test
    void aWrongStatusIsAConflictAndChangesNothing() throws Exception {
        UUID inProgress = assignedTo(technician, "IN_PROGRESS");
        UUID assigned = assignedTo(technician, "ASSIGNED");

        act(technician, inProgress, ACCEPTANCE, body(0, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        act(technician, assigned, RESOLUTION, body(0, RESOLUTION_NOTES))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        assertUnchanged(inProgress, "IN_PROGRESS", 0, 2);
        assertUnchanged(assigned, "ASSIGNED", 0, 2);
    }

    // T10: only a technician accepts or resolves (docs/security.md).
    @Test
    void otherRolesAreForbidden() throws Exception {
        UUID toAccept = assignedTo(technician, "ASSIGNED");
        UUID toResolve = assignedTo(technician, "IN_PROGRESS");

        for (UUID caller : new UUID[] {operator, administrator, citizen, analyst}) {
            expectForbidden(act(caller, toAccept, ACCEPTANCE, body(0, null)));
            expectForbidden(act(caller, toResolve, RESOLUTION, body(0, RESOLUTION_NOTES)));
        }

        assertUnchanged(toAccept, "ASSIGNED", 0, 2);
        assertUnchanged(toResolve, "IN_PROGRESS", 0, 2);
    }

    // ADR-0020, "Errors": a missing or negative version is invalid data, so it is a 422 only after
    // the role (403), the access (404) and the transition (409 INVALID_TRANSITION).
    @Test
    void anInvalidVersionIsCheckedAfterTheRoleTheAccessAndTheTransition() throws Exception {
        UUID assigned = assignedTo(technician, "ASSIGNED");
        UUID inProgress = assignedTo(technician, "IN_PROGRESS");

        for (String action : ACTIONS) {
            expectForbidden(act(citizen, assigned, action, "{}"));
            expectNotFound(act(otherTechnician, assigned, action, "{}"));
        }
        // Accepting is allowed from ASSIGNED and resolving from IN_PROGRESS: only the version
        // fails.
        for (String body : new String[] {"{}", "{\"version\": -1}"}) {
            expectUnprocessable(act(technician, assigned, ACCEPTANCE, body), "version");
            expectUnprocessable(act(technician, inProgress, RESOLUTION, body), "version");
        }
        // The other way round the transition is not allowed, which comes first.
        act(technician, inProgress, ACCEPTANCE, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        act(technician, assigned, RESOLUTION, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        assertUnchanged(assigned, "ASSIGNED", 0, 2);
        assertUnchanged(inProgress, "IN_PROGRESS", 0, 2);
    }

    // T11: once the incident is reassigned, the former technician can no longer act on it.
    @Test
    void aReassignedTechnicianGetsNotFound() throws Exception {
        UUID toAccept = assignedTo(technician, "ASSIGNED");
        UUID toResolve = assignedTo(technician, "IN_PROGRESS");
        for (UUID incident : List.of(toAccept, toResolve)) {
            jdbc.update("UPDATE assignment SET is_active = false WHERE incident_id = ?", incident);
            assign(incident, otherTechnician);
        }

        expectNotFound(act(technician, toAccept, ACCEPTANCE, body(0, null)));
        expectNotFound(act(technician, toResolve, RESOLUTION, body(0, RESOLUTION_NOTES)));

        assertUnchanged(toAccept, "ASSIGNED", 0, 2);
        assertUnchanged(toResolve, "IN_PROGRESS", 0, 2);
        // The new technician can.
        act(otherTechnician, toAccept, ACCEPTANCE, body(0, null)).andExpect(status().isOk());
    }

    // T12: the citizen sees IN_PROGRESS and RESOLVED, but not the notes nor who acted.
    @Test
    void theCitizenSeesTheStatusesButNotTheNotes() throws Exception {
        UUID incident = assignedTo(technician, "ASSIGNED");

        act(technician, incident, ACCEPTANCE, body(versionOf(incident), ACCEPTANCE_NOTE))
                .andExpect(status().isOk());
        act(technician, incident, RESOLUTION, body(versionOf(incident), RESOLUTION_NOTES))
                .andExpect(status().isOk());

        String history =
                mvc.perform(as(citizen, get(INCIDENTS + "/" + incident + "/status-changes")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<Map<String, Object>> entries = JsonPath.read(history, "$");
        assertThat(entries)
                .extracting(entry -> entry.get("newStatus"))
                .containsExactly("REPORTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED");
        assertThat(entries).extracting(entry -> entry.get("reason")).containsOnlyNulls();
        assertThat(entries)
                .allSatisfy(
                        entry -> assertThat(entry).doesNotContainKeys("actor", "visibleToCitizen"));
        assertThat(history)
                .doesNotContain(RESOLUTION_NOTES)
                .doesNotContain(ACCEPTANCE_NOTE)
                .doesNotContain(technician.toString());
    }

    // T13: an incident that does not exist is a 404; without a token it is a 401.
    @Test
    void unknownIncidentsAndRequestsWithoutAToken() throws Exception {
        UUID incident = assignedTo(technician, "ASSIGNED");
        UUID unknown = UUID.randomUUID();

        for (String action : ACTIONS) {
            expectNotFound(act(technician, unknown, action, body(0, RESOLUTION_NOTES)));
            mvc.perform(
                            post(actionPath(incident, action))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(0, RESOLUTION_NOTES)))
                    .andExpect(status().isUnauthorized());
        }

        assertUnchanged(incident, "ASSIGNED", 0, 2);
    }

    /**
     * An incident in {@code status}, actively assigned to {@code assignee}, with two history
     * entries: the report and the last change, which leaves it in {@code status}. Only the status
     * and the count matter to these tests, not the steps in between.
     */
    private UUID assignedTo(UUID assignee, String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, 'HIGH',"
                                + " 'Dark crossing near a school', 'MANUAL', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        category,
                        status,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(ASSIGNED_AT));
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        assign(incident, assignee);
        history(incident, citizen, REPORTED_AT, null, "REPORTED");
        history(incident, operator, ASSIGNED_AT, "VALIDATED", status);
        return incident;
    }

    private void assign(UUID incident, UUID assignee) {
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?, true)",
                incident,
                department,
                assignee,
                Timestamp.from(ASSIGNED_AT));
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
        assertThat(invalid).contains(fields);
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
