package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code createIncidentAssignment}, {@code listDepartments} and the {@code assignment} of {@code
 * IncidentResponse} (#184), with the cases of #72 that run over HTTP (T2 to T6, T8, T10 to T12, T14
 * and T15; T7 is every response checked against the contract) and the order of the checks of
 * ADR-0020. T9 and T13 are in {@code IncidentAssignmentServiceIT}.
 *
 * <p>The data is inserted with SQL on the Flyway schema and every check goes through HTTP; every
 * response is also validated against the contract ({@link ApiContract}). Each test runs in a
 * transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentAssignmentApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String DEPARTMENTS = "/api/v1/departments";
    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID department;
    private UUID category;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN, "Carmen Citizen");
        operator = user(Role.MUNICIPAL_OPERATOR, "Olga Operator");
        administrator = user(Role.ADMINISTRATOR, "Adrián Admin");
        technician = user(Role.TECHNICIAN, "Tomás Technician");
        otherTechnician = user(Role.TECHNICIAN, "Teresa Technician");
        department = department("Street lighting " + UUID.randomUUID(), true);
        category =
                jdbc.queryForObject(
                        "INSERT INTO category (name) VALUES ('Lighting') RETURNING id", UUID.class);
    }

    // T2
    @Test
    void assigningAValidatedIncidentCreatesTheAssignmentAndMovesItToAssigned() throws Exception {
        UUID incident = incident("VALIDATED");

        String body =
                assign(
                                operator,
                                incident,
                                Map.of(
                                        "version",
                                        0,
                                        "departmentId",
                                        department,
                                        "technicianId",
                                        technician,
                                        "team",
                                        "Night shift"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(incident.toString()))
                        .andExpect(jsonPath("$.status").value("ASSIGNED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat((Integer) JsonPath.read(body, "$.version")).isPositive();
        assertThat((String) JsonPath.read(body, "$.assignment.department.id"))
                .isEqualTo(department.toString());
        assertThat((String) JsonPath.read(body, "$.assignment.technician.id"))
                .isEqualTo(technician.toString());
        assertThat((String) JsonPath.read(body, "$.assignment.technician.name"))
                .isEqualTo("Tomás Technician");
        assertThat((String) JsonPath.read(body, "$.assignment.team")).isEqualTo("Night shift");
        assertThat(assignments(incident))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("department_id")).isEqualTo(department);
                            assertThat(row.get("technician_id")).isEqualTo(technician);
                            assertThat(row.get("team")).isEqualTo("Night shift");
                            assertThat(row.get("is_active")).isEqualTo(true);
                        });
        assertThat(lastChange(incident))
                .containsEntry("previous_status", "VALIDATED")
                .containsEntry("new_status", "ASSIGNED")
                .containsEntry("actor_id", operator);
        assertThat(statusOf(incident)).isEqualTo("ASSIGNED");
    }

    // T11
    @Test
    void anAdministratorAssignsAReopenedIncidentToTheDepartmentOnly() throws Exception {
        UUID incident = incident("REOPENED");

        assign(administrator, incident, Map.of("version", 0, "departmentId", department))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignment.technician").value(nullValue()))
                .andExpect(jsonPath("$.assignment.team").value(nullValue()));

        // In nobody's queue: no technician sees it.
        mvc.perform(
                        get(INCIDENTS)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(technician, Role.TECHNICIAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]", incident).isEmpty());

        assertThat(assignments(incident))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("technician_id")).isNull();
                            assertThat(row.get("team")).isNull();
                        });
    }

    // T3
    @Test
    void reassigningDeactivatesThePreviousAssignmentAndKeepsIt() throws Exception {
        UUID incident = incident("ASSIGNED");
        UUID previous = assignment(incident, technician);

        assign(
                        operator,
                        incident,
                        Map.of(
                                "version", 0,
                                "departmentId", department,
                                "technicianId", otherTechnician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(
                        jsonPath("$.assignment.technician.id").value(otherTechnician.toString()));

        var rows = assignments(incident);
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(row -> row.get("id").equals(previous))
                .singleElement()
                .satisfies(row -> assertThat(row.get("is_active")).isEqualTo(false));
        assertThat(rows)
                .filteredOn(row -> Boolean.TRUE.equals(row.get("is_active")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("technician_id")).isEqualTo(otherTechnician));
        assertThat(lastChange(incident))
                .containsEntry("previous_status", "ASSIGNED")
                .containsEntry("new_status", "ASSIGNED");
    }

    // T4: invalid status
    @Test
    void aReportedIncidentCannotBeAssigned() throws Exception {
        UUID incident = incident("REPORTED");

        expectConflict(
                assign(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                0,
                                "departmentId",
                                department,
                                "technicianId",
                                technician)),
                "INVALID_TRANSITION");

        assertUnchanged(incident, "REPORTED");
    }

    // T12
    @Test
    void noOtherStatusCanBeAssigned() throws Exception {
        for (String status : List.of("IN_PROGRESS", "RESOLVED", "REJECTED", "CLOSED")) {
            UUID incident = incident(status);
            expectConflict(
                    assign(operator, incident, Map.of("version", 0, "departmentId", department)),
                    "INVALID_TRANSITION");
            assertUnchanged(incident, status);
        }
    }

    @Test
    void staffSeeTheActiveAssignmentInTheDetailAndNullWithoutOne() throws Exception {
        UUID unassigned = incident("VALIDATED");
        UUID assigned = incident("ASSIGNED");
        assignment(assigned, technician, false);
        UUID active = assignment(assigned, otherTechnician, true);

        asStaff(INCIDENTS + "/" + unassigned)
                .andExpect(jsonPath("$.assignment").value(nullValue()));
        asStaff(INCIDENTS + "/" + assigned)
                .andExpect(jsonPath("$.assignment.id").value(active.toString()))
                .andExpect(jsonPath("$.assignment.technician.name").value("Teresa Technician"));
    }

    // T4: a user who is not an active technician
    @Test
    void onlyAnActiveTechnicianCanBeAssigned() throws Exception {
        UUID disabled = user(Role.TECHNICIAN, "Disabled Technician");
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", disabled);

        for (UUID notATechnician : List.of(operator, citizen, disabled, UUID.randomUUID())) {
            UUID incident = incident("VALIDATED");
            expectInvalid(
                    assign(
                            operator,
                            incident,
                            Map.of(
                                    "version", 0,
                                    "departmentId", department,
                                    "technicianId", notATechnician)),
                    "technicianId");
            assertUnchanged(incident, "VALIDATED");
        }
    }

    // T10
    @Test
    void theDepartmentMustExistAndBeActive() throws Exception {
        UUID closed = department("Closed department " + UUID.randomUUID(), false);

        for (Map<String, Object> body :
                List.<Map<String, Object>>of(
                        Map.of("version", 0),
                        Map.of("version", 0, "departmentId", UUID.randomUUID()),
                        Map.of("version", 0, "departmentId", closed))) {
            UUID incident = incident("VALIDATED");
            expectInvalid(assign(operator, incident, body), "departmentId");
            assertUnchanged(incident, "VALIDATED");
        }
    }

    @Test
    void everyInvalidFieldIsListedAtOnce() throws Exception {
        UUID incident = incident("VALIDATED");
        UUID closed = department("Closed department " + UUID.randomUUID(), false);

        String body =
                assign(
                                operator,
                                incident,
                                Map.of(
                                        "version",
                                        0,
                                        "departmentId",
                                        closed,
                                        "technicianId",
                                        citizen,
                                        "team",
                                        "  "))
                        .andExpect(status().isUnprocessableContent())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<String> fields = JsonPath.read(body, "$.errors[*].field");
        assertThat(fields).containsExactlyInAnyOrder("departmentId", "technicianId", "team");
    }

    // T8. ADR-0020: a stale version is refused before anything else is looked at.
    @Test
    void aStaleVersionIsAConcurrentModification() throws Exception {
        UUID incident = incident("VALIDATED");
        jdbc.update("UPDATE incident SET version = 4 WHERE id = ?", incident);

        expectConflict(
                assign(operator, incident, Map.of("version", 3, "departmentId", department)),
                "CONCURRENT_MODIFICATION");

        assertThat(assignments(incident)).isEmpty();
        assertThat(statusOf(incident)).isEqualTo("VALIDATED");
    }

    // ADR-0020: 404, then 409 (version), then 409 (transition), then 422.
    @Test
    void theChecksFollowTheOrderOfAdr0020() throws Exception {
        UUID reported = incident("REPORTED");

        jdbc.update("UPDATE incident SET version = 1 WHERE id = ?", reported);
        Map<String, Object> stale =
                Map.of("version", 0, "departmentId", UUID.randomUUID(), "technicianId", citizen);
        Map<String, Object> current =
                Map.of("version", 1, "departmentId", UUID.randomUUID(), "technicianId", citizen);

        expectConflict(assign(operator, reported, stale), "CONCURRENT_MODIFICATION");
        expectConflict(assign(operator, reported, current), "INVALID_TRANSITION");

        assign(operator, UUID.randomUUID(), stale)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void theVersionIsRequired() throws Exception {
        UUID incident = incident("VALIDATED");

        expectInvalid(assign(operator, incident, Map.of("departmentId", department)), "version");
        expectInvalid(
                assign(operator, incident, Map.of("version", -1, "departmentId", department)),
                "version");
    }

    @Test
    void aBodyThatIsNotJsonIsMalformed() throws Exception {
        mvc.perform(
                        post(path(incident("VALIDATED")))
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(operator, Role.MUNICIPAL_OPERATOR))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // T5
    @Test
    void theCitizenSeesTheIncidentAssignedButNotWhoWorksOnIt() throws Exception {
        UUID incident = incident("VALIDATED");
        assign(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                0,
                                "departmentId",
                                department,
                                "technicianId",
                                technician,
                                "team",
                                "Night shift"))
                .andExpect(status().isOk());

        String history = asCitizen(INCIDENTS + "/" + incident + "/status-changes");
        String detail = asCitizen(INCIDENTS + "/" + incident);

        List<String> statuses = JsonPath.read(history, "$[*].newStatus");
        assertThat(statuses).endsWith("ASSIGNED");
        assertThat(JsonPath.<Map<String, Object>>read(detail, "$")).doesNotContainKey("assignment");
        for (String body : List.of(history, detail)) {
            assertThat(body)
                    .doesNotContain(technician.toString())
                    .doesNotContain("Tomás Technician")
                    .doesNotContain(department.toString())
                    .doesNotContain("Night shift")
                    .doesNotContain("actor");
        }
    }

    // T6 and T15
    @Test
    void onlyOperatorsAndAdministratorsCanAssign() throws Exception {
        UUID incident = incident("VALIDATED");
        Map<String, Object> body = Map.of("version", 0, "departmentId", department);

        for (Role role : List.of(Role.TECHNICIAN, Role.CITIZEN, Role.ANALYST)) {
            mvc.perform(
                            post(path(incident))
                                    .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json(body)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(
                        post(path(incident))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                .andExpect(status().isUnauthorized());
        assertUnchanged(incident, "VALIDATED");
    }

    // T14
    @Test
    void staffListTheActiveDepartmentsByName() throws Exception {
        String hidden = "Closed department " + UUID.randomUUID();
        department(hidden, false);

        for (var caller :
                List.of(
                        Map.entry(operator, Role.MUNICIPAL_OPERATOR),
                        Map.entry(technician, Role.TECHNICIAN),
                        Map.entry(administrator, Role.ADMINISTRATOR))) {
            String body =
                    mvc.perform(
                                    get(DEPARTMENTS)
                                            .header(
                                                    HttpHeaders.AUTHORIZATION,
                                                    tokens.bearer(
                                                            caller.getKey(), caller.getValue())))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            List<String> names = JsonPath.read(body, "$[*].name");
            List<String> ids = JsonPath.read(body, "$[*].id");
            assertThat(names).contains("Public Lighting", "Mobility").doesNotContain(hidden);
            assertThat(names).isSortedAccordingTo(String::compareTo);
            assertThat(ids).contains(department.toString());
        }
    }

    // T14 and T15
    @Test
    void citizensAndAnalystsCannotListTheDepartments() throws Exception {
        for (Role role : List.of(Role.CITIZEN, Role.ANALYST)) {
            mvc.perform(get(DEPARTMENTS).header(HttpHeaders.AUTHORIZATION, tokens.bearer(role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(DEPARTMENTS)).andExpect(status().isUnauthorized());
    }

    private ResultActions assign(UUID caller, UUID incident, Map<String, Object> body)
            throws Exception {
        return mvc.perform(
                post(path(incident))
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)));
    }

    private String asCitizen(String path) throws Exception {
        return mvc.perform(
                        get(path)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(citizen, Role.CITIZEN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static void expectConflict(ResultActions result, String code) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static void expectInvalid(ResultActions result, String field) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    private void assertUnchanged(UUID incident, String expectedStatus) {
        assertThat(statusOf(incident)).isEqualTo(expectedStatus);
        assertThat(assignments(incident)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM status_change WHERE incident_id = ?",
                                Long.class,
                                incident))
                .isZero();
    }

    private static String path(UUID incident) {
        return INCIDENTS + "/" + incident + "/assignments";
    }

    // The bodies only hold numbers, UUIDs and plain text without quotes.
    private static String json(Map<String, Object> body) {
        var fields =
                body.entrySet().stream()
                        .map(
                                entry ->
                                        entry.getValue() instanceof Number
                                                ? "\"%s\": %s"
                                                        .formatted(entry.getKey(), entry.getValue())
                                                : "\"%s\": \"%s\""
                                                        .formatted(
                                                                entry.getKey(), entry.getValue()))
                        .toList();
        return "{" + String.join(", ", fields) + "}";
    }

    private UUID user(Role role, String name) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, '{bcrypt}not-used', ?)"
                        + " RETURNING id",
                UUID.class,
                name,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private UUID department(String name, boolean active) {
        return jdbc.queryForObject(
                "INSERT INTO department (name, is_active) VALUES (?, ?) RETURNING id",
                UUID.class,
                name,
                active);
    }

    private UUID incident(String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status, priority,"
                                + " priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, 'HIGH',"
                                + " 'Dark crossing', 'MANUAL', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        category,
                        status,
                        Timestamp.from(MONDAY),
                        Timestamp.from(MONDAY));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        return incident;
    }

    private UUID assignment(UUID incident, UUID assignedTechnician) {
        return assignment(incident, assignedTechnician, true);
    }

    private UUID assignment(UUID incident, UUID assignedTechnician, boolean active) {
        return jdbc.queryForObject(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at,"
                        + " is_active) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class,
                incident,
                department,
                assignedTechnician,
                Timestamp.from(MONDAY),
                active);
    }

    private ResultActions asStaff(String path) throws Exception {
        return mvc.perform(
                        get(path)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(operator, Role.MUNICIPAL_OPERATOR)))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> assignments(UUID incident) {
        return jdbc.queryForList(
                "SELECT id, department_id, technician_id, team, is_active FROM assignment WHERE incident_id = ?",
                incident);
    }

    private Map<String, Object> lastChange(UUID incident) {
        return jdbc.queryForMap(
                "SELECT previous_status, new_status, actor_id FROM status_change WHERE incident_id = ?"
                        + " ORDER BY instant DESC LIMIT 1",
                incident);
    }

    private String statusOf(UUID incident) {
        return jdbc.queryForObject(
                "SELECT status FROM incident WHERE id = ?", String.class, incident);
    }
}
