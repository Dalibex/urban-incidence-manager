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
 * Acceptance tests of #71 (T3 to T13) for the operator's actions on a reported incident of #188:
 * {@code createIncidentValidation}.
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
class IncidentOperatorActionApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String VALIDATION = "validation";
    private static final String[] ACTIONS = {VALIDATION};

    /** Every status but {@code REPORTED}, the only one these actions start from (ADR-0020). */
    private static final String[] OTHER_STATUSES = {
        "VALIDATED", "REJECTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "REOPENED", "CLOSED"
    };

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final String VALIDATION_NOTE = "Checked with the street lighting map: lamp 118.";

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
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        for (UUID incident : createdIncidents) {
            jdbc.update("DELETE FROM notification WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM status_change WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update("DELETE FROM category WHERE id = ?", category);
        // The audit trail is append-only and references its actor: a user who acted in it stays,
        // with a random email, so it cannot clash with another test.
        for (UUID user : createdUsers) {
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                    user);
        }
    }

    // T3: an operator validates a classified incident: REPORTED to VALIDATED (ADR-0020).
    @Test
    void anOperatorValidatesAClassifiedIncident() throws Exception {
        UUID incident = classified("REPORTED");
        int version = versionOf(incident);

        act(operator, incident, VALIDATION, body(version, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incident.toString()))
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.version").value(version + 1));

        assertThat(statusOf(incident)).isEqualTo("VALIDATED");
        assertThat(historyCount(incident)).isEqualTo(2);
        assertThat(lastHistoryEntry(incident))
                .containsEntry("previous_status", "REPORTED")
                .containsEntry("new_status", "VALIDATED")
                .containsEntry("actor_id", operator)
                .containsEntry("reason", null)
                .containsEntry("visible_to_citizen", true);
    }

    // T3: an administrator validates too, with an optional note; a blank one is refused instead of
    // stored, and so is one of no-break spaces, which the "\S" of the contract does not accept.
    @Test
    void theValidationNoteIsOptionalButNeverBlank() throws Exception {
        UUID withNote = classified("REPORTED");
        UUID blankNote = classified("REPORTED");

        act(administrator, withNote, VALIDATION, body(versionOf(withNote), VALIDATION_NOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        for (String note : new String[] {"", "   ", " "}) {
            expectUnprocessable(act(operator, blankNote, VALIDATION, body(0, note)), "reason");
        }

        assertThat(lastHistoryEntry(withNote))
                .containsEntry("actor_id", administrator)
                .containsEntry("reason", VALIDATION_NOTE);
        assertUnchanged(blankNote, "REPORTED", 0, 1);
    }

    // T3: validating without a category or a priority is 422 on what is missing, and changes
    // nothing.
    @Test
    void validatingWithoutClassificationIsUnprocessable() throws Exception {
        UUID withoutEither = reported(false, false);
        UUID withoutCategory = reported(false, true);
        UUID withoutPriority = reported(true, false);

        expectUnprocessable(
                act(operator, withoutEither, VALIDATION, body(0, null)), "category", "priority");
        expectUnprocessable(act(operator, withoutCategory, VALIDATION, body(0, null)), "category");
        expectUnprocessable(act(operator, withoutPriority, VALIDATION, body(0, null)), "priority");

        assertUnchanged(withoutEither, "REPORTED", 0, 1);
        assertUnchanged(withoutCategory, "REPORTED", 0, 1);
        assertUnchanged(withoutPriority, "REPORTED", 0, 1);
    }

    // T6: an incident that is no longer REPORTED cannot be acted on: 409, nothing changes.
    @Test
    void anIncidentThatIsNotReportedIsAConflict() throws Exception {
        for (String status : OTHER_STATUSES) {
            UUID incident = classified(status);
            for (String action : ACTIONS) {
                act(operator, incident, action, validBody(action, 0))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
            }
            assertUnchanged(incident, status, 0, 1);
        }
    }

    // T7 and T13: only operators and administrators act on a reported incident
    // (docs/security.md); a technician, a citizen, even the reporter, and an analyst get 403.
    @Test
    void otherRolesAreForbidden() throws Exception {
        UUID incident = classified("REPORTED");

        for (UUID caller : new UUID[] {technician, citizen, analyst}) {
            for (String action : ACTIONS) {
                expectForbidden(act(caller, incident, action, validBody(action, 0)));
            }
        }

        assertUnchanged(incident, "REPORTED", 0, 1);
    }

    // T9: a version read before someone else's change is refused on every action, with no history
    // entry.
    @Test
    void aStaleVersionIsAConcurrentModificationOnEveryAction() throws Exception {
        UUID incident = classified("REPORTED");
        jdbc.update("UPDATE incident SET version = version + 1 WHERE id = ?", incident);

        for (String action : ACTIONS) {
            act(operator, incident, action, validBody(action, 0))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        }

        assertUnchanged(incident, "REPORTED", 1, 1);
    }

    // T10: two operators validate the same version; the second one is refused, so the history has
    // one validation. IncidentLifecycleServiceIT covers two requests that really overlap.
    @Test
    void theSecondOfTwoOperatorsOnTheSameVersionIsAConflict() throws Exception {
        UUID incident = classified("REPORTED");
        int version = versionOf(incident);

        act(operator, incident, VALIDATION, body(version, null)).andExpect(status().isOk());
        act(otherOperator, incident, VALIDATION, body(version, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertUnchanged(incident, "VALIDATED", version + 1, 2);
        assertThat(lastHistoryEntry(incident)).containsEntry("actor_id", operator);
    }

    // ADR-0020, "Errors": a missing or negative version is invalid data, so it is a 422 only after
    // the role (403), the access (404) and the transition (409 INVALID_TRANSITION).
    @Test
    void anInvalidVersionIsCheckedAfterTheRoleTheAccessAndTheTransition() throws Exception {
        UUID reported = classified("REPORTED");
        UUID validated = classified("VALIDATED");

        for (String action : ACTIONS) {
            expectForbidden(act(technician, reported, action, "{}"));
            expectNotFound(act(operator, UUID.randomUUID(), action, "{}"));
            for (String body : new String[] {"{}", "{\"version\": -1}"}) {
                expectUnprocessable(act(operator, reported, action, body), "version");
                act(operator, validated, action, body)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
            }
        }

        assertUnchanged(reported, "REPORTED", 0, 1);
        assertUnchanged(validated, "VALIDATED", 0, 1);
    }

    // T12: the citizen sees the validation, but not the internal note nor who validated.
    @Test
    void theCitizenSeesTheValidationButNotTheNote() throws Exception {
        UUID incident = classified("REPORTED");

        act(operator, incident, VALIDATION, body(versionOf(incident), VALIDATION_NOTE))
                .andExpect(status().isOk());

        List<Map<String, Object>> entries = citizenHistory(incident);
        assertThat(entries)
                .extracting(entry -> entry.get("newStatus"))
                .containsExactly("REPORTED", "VALIDATED");
        assertThat(entries).extracting(entry -> entry.get("reason")).containsOnlyNulls();
        assertThat(entries)
                .allSatisfy(
                        entry -> assertThat(entry).doesNotContainKeys("actor", "visibleToCitizen"));
    }

    // T13: an incident that does not exist is a 404; without a token it is a 401.
    @Test
    void unknownIncidentsAndRequestsWithoutAToken() throws Exception {
        UUID incident = classified("REPORTED");

        for (String action : ACTIONS) {
            expectNotFound(act(operator, UUID.randomUUID(), action, validBody(action, 0)));
            mvc.perform(
                            post(actionPath(incident, action))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(validBody(action, 0)))
                    .andExpect(status().isUnauthorized());
        }

        assertUnchanged(incident, "REPORTED", 0, 1);
    }

    /** An incident in {@code status} with a category and a priority. */
    private UUID classified(String status) {
        return incident(status, true, true);
    }

    /** A reported incident, with or without its category and its priority. */
    private UUID reported(boolean withCategory, boolean withPriority) {
        return incident("REPORTED", withCategory, withPriority);
    }

    /**
     * An incident in {@code status} with one history entry, the report. Only the status and the
     * count matter to these tests, not the steps in between.
     */
    private UUID incident(String status, boolean withCategory, boolean withPriority) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, ?, ?, ?,"
                                + " ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        withCategory ? category : null,
                        status,
                        withPriority ? "HIGH" : null,
                        withPriority ? "Dark crossing near a school" : null,
                        withPriority ? "MANUAL" : null,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(REPORTED_AT));
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status,"
                        + " new_status, visible_to_citizen) VALUES (?, ?, ?, NULL, 'REPORTED', true)",
                incident,
                citizen,
                Timestamp.from(REPORTED_AT));
        return incident;
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
    private List<Map<String, Object>> citizenHistory(UUID incident) throws Exception {
        String history =
                mvc.perform(as(citizen, get(INCIDENTS + "/" + incident + "/status-changes")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(history, "$");
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

    /** A body the action accepts on a classified, reported incident read at {@code version}. */
    private static String validBody(String action, int version) {
        return switch (action) {
            case VALIDATION -> body(version, null);
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
