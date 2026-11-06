package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #78 (T3 to T6, T9 to T13, and the API part of T12) for the staff filters of
 * {@code listIncidents} of #182: {@code categoryId}, {@code priority}, {@code reportedFrom}, {@code
 * reportedTo} and {@code departmentId}, next to {@code status}.
 *
 * <p>They are written from the issues, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}: the data is inserted with SQL and every check goes through HTTP. Every
 * response is also validated against the contract ({@link ApiContract}).
 *
 * <p>Each test runs inside a transaction that is rolled back. Other incidents may exist in the
 * database, so every check of a staff listing narrows it with a category or a department created by
 * the test, or with dates no other test uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentListFilterApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";

    // Far from the dates of the other tests and of the real clock, so a date filter only finds the
    // incidents of this class.
    private static final Instant JAN_1 = Instant.parse("2031-01-01T00:00:00Z");
    private static final Instant JAN_2 = Instant.parse("2031-01-02T00:00:00Z");
    private static final Instant JAN_3 = Instant.parse("2031-01-03T00:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;
    private UUID otherCitizen;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID analyst;
    private UUID lighting;
    private UUID potholes;
    private UUID streetLighting;
    private UUID roads;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN);
        otherCitizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        otherTechnician = user(Role.TECHNICIAN);
        analyst = user(Role.ANALYST);
        lighting = category("Lighting");
        potholes = category("Potholes");
        streetLighting = department("Street lighting");
        roads = department("Roads");
    }

    // T3: categoryId, alone and repeated.
    @Test
    void categoryFilterKeepsOnlyThoseCategories() throws Exception {
        UUID lamp = incident(citizen, "VALIDATED", "LOW", lighting, JAN_1);
        UUID hole = incident(citizen, "VALIDATED", "LOW", potholes, JAN_1);

        assertThat(listed(operator, "categoryId", lighting.toString()))
                .containsExactly(lamp.toString());
        assertThat(
                        listed(
                                operator,
                                "categoryId",
                                lighting.toString(),
                                "categoryId",
                                potholes.toString()))
                .containsExactlyInAnyOrder(lamp.toString(), hole.toString());
    }

    // T3: priority, alone and repeated.
    @Test
    void priorityFilterKeepsOnlyThosePriorities() throws Exception {
        UUID high = incident(citizen, "VALIDATED", "HIGH", lighting, JAN_1);
        UUID medium = incident(citizen, "VALIDATED", "MEDIUM", lighting, JAN_1);
        incident(citizen, "VALIDATED", "LOW", lighting, JAN_1);

        assertThat(listed(operator, "categoryId", lighting.toString(), "priority", "HIGH"))
                .containsExactly(high.toString());
        assertThat(
                        listed(
                                operator,
                                "categoryId",
                                lighting.toString(),
                                "priority",
                                "HIGH",
                                "priority",
                                "MEDIUM"))
                .containsExactlyInAnyOrder(high.toString(), medium.toString());
    }

    // T3: reportedFrom is included and reportedTo is excluded, whatever the offset.
    @Test
    void dateFilterIncludesTheStartAndExcludesTheEnd() throws Exception {
        UUID atStart = incident(citizen, "REPORTED", null, lighting, JAN_1);
        UUID inside = incident(citizen, "REPORTED", null, lighting, JAN_1.plusSeconds(3600));
        UUID atEnd = incident(citizen, "REPORTED", null, lighting, JAN_2);
        UUID before = incident(citizen, "REPORTED", null, lighting, JAN_1.minusSeconds(1));

        assertThat(
                        listed(
                                operator,
                                "reportedFrom",
                                "2031-01-01T00:00:00Z",
                                "reportedTo",
                                "2031-01-02T00:00:00Z"))
                .containsExactlyInAnyOrder(atStart.toString(), inside.toString());
        // The same instants written in another offset.
        assertThat(
                        listed(
                                operator,
                                "reportedFrom",
                                "2031-01-01T01:00:00+01:00",
                                "reportedTo",
                                "2031-01-02T01:00:00+01:00"))
                .containsExactlyInAnyOrder(atStart.toString(), inside.toString());
        // Each limit alone.
        assertThat(
                        listed(
                                operator,
                                "categoryId",
                                lighting.toString(),
                                "reportedTo",
                                "2031-01-01T00:00:00Z"))
                .containsExactly(before.toString());
        assertThat(
                        listed(
                                operator,
                                "categoryId",
                                lighting.toString(),
                                "reportedFrom",
                                "2031-01-02T00:00:00Z"))
                .containsExactly(atEnd.toString());
    }

    // T3: every filter used must match.
    @Test
    void filtersCombineSoEveryOneMustMatch() throws Exception {
        UUID match = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_1);
        assign(match, streetLighting, technician, true);
        UUID otherStatus = incident(citizen, "IN_PROGRESS", "HIGH", lighting, JAN_1);
        assign(otherStatus, streetLighting, technician, true);
        UUID otherPriority = incident(citizen, "ASSIGNED", "LOW", lighting, JAN_1);
        assign(otherPriority, streetLighting, technician, true);
        UUID otherDepartment = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_1);
        assign(otherDepartment, roads, technician, true);
        UUID otherDate = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_3);
        assign(otherDate, streetLighting, technician, true);

        assertThat(
                        listed(
                                administrator,
                                "status",
                                "ASSIGNED",
                                "categoryId",
                                lighting.toString(),
                                "priority",
                                "HIGH",
                                "departmentId",
                                streetLighting.toString(),
                                "reportedFrom",
                                "2031-01-01T00:00:00Z",
                                "reportedTo",
                                "2031-01-02T00:00:00Z"))
                .containsExactly(match.toString());
    }

    // T4: with filters, pages neither repeat nor skip rows, also when every row ties.
    @Test
    void filteredPagesAreStable() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(incident(citizen, "VALIDATED", "LOW", lighting, JAN_1).toString());
        }

        for (String sort : new String[] {"reportedAt,desc", "updatedAt,desc"}) {
            List<String> firstRun = pages(sort);
            List<String> secondRun = pages(sort);

            assertThat(firstRun)
                    .as("sorted by %s", sort)
                    .containsExactlyInAnyOrderElementsOf(created)
                    .doesNotHaveDuplicates();
            assertThat(secondRun).as("sorted by %s", sort).isEqualTo(firstRun);
        }
    }

    // T5: a technician's filters stay inside their active assignments.
    @Test
    void aTechniciansFiltersNeverWidenTheirQueue() throws Exception {
        UUID mine = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_1);
        assign(mine, streetLighting, technician, true);
        UUID someoneElses = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_1);
        assign(someoneElses, streetLighting, otherTechnician, true);
        UUID unassigned = incident(citizen, "VALIDATED", "HIGH", lighting, JAN_1);

        assertThat(listed(technician, "departmentId", streetLighting.toString()))
                .containsExactly(mine.toString());
        assertThat(listed(technician, "categoryId", lighting.toString(), "priority", "HIGH"))
                .containsExactly(mine.toString());
        assertThat(listed(operator, "categoryId", lighting.toString()))
                .containsExactlyInAnyOrder(
                        mine.toString(), someoneElses.toString(), unassigned.toString());
    }

    // T5: a citizen's status filter stays inside their own reports.
    @Test
    void aCitizensStatusFilterNeverWidensTheirReports() throws Exception {
        UUID mine = incident(citizen, "REPORTED", null, lighting, JAN_1);
        incident(otherCitizen, "REPORTED", null, lighting, JAN_1);

        assertThat(listed(citizen, "status", "REPORTED")).containsExactly(mine.toString());
    }

    // T6: a parameter the contract does not document, and an unknown sort property.
    @Test
    void anUnknownFilterOrSortIsABadRequest() throws Exception {
        expectMalformed(operator, "colour", "red");
        expectMalformed(operator, "categoryID", lighting.toString());
        expectMalformed(operator, "sort", "foo,asc");
        expectMalformed(citizen, "colour", "red");
    }

    // T9: a citizen may only filter by status.
    @Test
    void aCitizenWithAStaffFilterIsForbidden() throws Exception {
        incident(citizen, "REPORTED", null, lighting, JAN_1);

        String[][] staffFilters = {
            {"categoryId", lighting.toString()},
            {"priority", "HIGH"},
            {"reportedFrom", "2031-01-01T00:00:00Z"},
            {"reportedTo", "2031-01-02T00:00:00Z"},
            {"departmentId", streetLighting.toString()},
            {"status", "REPORTED", "priority", "LOW"}
        };
        for (String[] filter : staffFilters) {
            expectForbidden(citizen, filter);
        }
    }

    // T10: values that cannot be read are a 400, never a 500 or a silent default.
    @Test
    void invalidFilterValuesAreABadRequest() throws Exception {
        String[][] invalid = {
            {"status", "FOO"},
            {"priority", "URGENT"},
            {"priority", "high"},
            {"categoryId", "not-a-uuid"},
            {"departmentId", "42"},
            {"reportedFrom", "yesterday"},
            {"reportedFrom", "2031-01-01"},
            // A local date-time would depend on the zone of the server.
            {"reportedFrom", "2031-01-01T00:00"},
            {"reportedTo", "2031-01-01T00:00:00"},
            // The contract says reportedFrom must be earlier.
            {"reportedFrom", "2031-01-02T00:00:00Z", "reportedTo", "2031-01-01T00:00:00Z"},
            {"reportedFrom", "2031-01-01T00:00:00Z", "reportedTo", "2031-01-01T00:00:00Z"},
            // An empty value.
            {"categoryId", ""},
            {"status", ""}
        };
        for (String[] query : invalid) {
            expectMalformed(operator, query);
        }
    }

    // T11: only the active assignment counts for the department.
    @Test
    void theDepartmentFilterOnlyUsesTheActiveAssignment() throws Exception {
        UUID active = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_1);
        assign(active, streetLighting, technician, true);
        UUID reassigned = incident(citizen, "ASSIGNED", "HIGH", lighting, JAN_1);
        assign(reassigned, streetLighting, technician, false);
        assign(reassigned, roads, otherTechnician, true);
        UUID unassigned = incident(citizen, "VALIDATED", "HIGH", lighting, JAN_1);

        assertThat(listed(operator, "departmentId", streetLighting.toString()))
                .containsExactly(active.toString());
        assertThat(listed(operator, "departmentId", roads.toString()))
                .containsExactly(reassigned.toString());
        assertThat(
                        listed(
                                operator,
                                "departmentId",
                                streetLighting.toString(),
                                "departmentId",
                                roads.toString()))
                .containsExactlyInAnyOrder(active.toString(), reassigned.toString())
                .doesNotContain(unassigned.toString());
    }

    // T12: filters that match nothing give an empty page, not an error.
    @Test
    void filtersThatMatchNothingGiveAnEmptyPage() throws Exception {
        incident(citizen, "VALIDATED", "LOW", lighting, JAN_1);

        list(operator, "categoryId", lighting.toString(), "priority", "HIGH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        list(citizen, "status", "CLOSED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // T13: the analyst cannot list, with or without filters; without a token it is a 401.
    @Test
    void theAnalystIsForbiddenAndNoTokenIsUnauthorized() throws Exception {
        expectForbidden(analyst, "categoryId", lighting.toString());
        expectForbidden(analyst, "priority", "HIGH");
        mvc.perform(get(INCIDENTS).param("categoryId", lighting.toString()))
                .andExpect(status().isUnauthorized());
    }

    private List<String> pages(String sort) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ids.addAll(
                    ids(
                            list(
                                            operator,
                                            "categoryId",
                                            lighting.toString(),
                                            "size",
                                            "2",
                                            "page",
                                            String.valueOf(page),
                                            "sort",
                                            sort)
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString()));
        }
        return ids;
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    private UUID category(String name) {
        return jdbc.queryForObject(
                "INSERT INTO category (name) VALUES (?) RETURNING id",
                UUID.class,
                name + " " + UUID.randomUUID());
    }

    private UUID department(String name) {
        return jdbc.queryForObject(
                "INSERT INTO department (name) VALUES (?) RETURNING id",
                UUID.class,
                name + " " + UUID.randomUUID());
    }

    /** An incident with its location; {@code priority} may be null, as before validation. */
    private UUID incident(
            UUID reporter, String status, String priority, UUID categoryId, Instant reportedAt) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, ?, ?, ?, ?, ?)"
                                + " RETURNING id",
                        UUID.class,
                        reporter,
                        categoryId,
                        status,
                        priority,
                        priority == null ? null : "Set by the test",
                        priority == null ? null : "MANUAL",
                        Timestamp.from(reportedAt),
                        Timestamp.from(reportedAt));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        return incident;
    }

    private void assign(UUID incident, UUID department, UUID assignedTechnician, boolean active) {
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?, ?)",
                incident,
                department,
                assignedTechnician,
                Timestamp.from(JAN_1),
                active);
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    /** {@code GET /incidents} as {@code caller}, with the query parameters as name, value pairs. */
    private ResultActions list(UUID caller, String... nameValuePairs) throws Exception {
        MockHttpServletRequestBuilder request =
                get(INCIDENTS)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller)));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mvc.perform(request);
    }

    /** The ids of the first page, which holds all the incidents of a test (size 100). */
    private List<String> listed(UUID caller, String... nameValuePairs) throws Exception {
        String[] withSize = new String[nameValuePairs.length + 2];
        System.arraycopy(nameValuePairs, 0, withSize, 0, nameValuePairs.length);
        withSize[nameValuePairs.length] = "size";
        withSize[nameValuePairs.length + 1] = "100";
        return ids(
                list(caller, withSize)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private static List<String> ids(String pageBody) {
        return JsonPath.read(pageBody, "$.content[*].id");
    }

    /** {@code GET /incidents} with {@code query} answers 400 {@code MALFORMED_REQUEST}. */
    private void expectMalformed(UUID caller, String... query) throws Exception {
        var response = list(caller, query).andReturn().getResponse();
        String what = String.join(" ", query);
        assertThat(response.getStatus()).as("status for %s", what).isEqualTo(400);
        assertThat(response.getContentType())
                .as("type for %s", what)
                .startsWith("application/problem+json");
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .as("code for %s", what)
                .isEqualTo("MALFORMED_REQUEST");
    }

    /** {@code GET /incidents} with {@code query} answers 403. */
    private void expectForbidden(UUID caller, String... query) throws Exception {
        var response = list(caller, query).andReturn().getResponse();
        String what = String.join(" ", query);
        assertThat(response.getStatus()).as("status for %s", what).isEqualTo(403);
        assertThat(response.getContentType())
                .as("type for %s", what)
                .startsWith("application/problem+json");
    }
}
