package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #258 (T1 to T4, T12 to T16 and T20) for {@code getIncidentStatistics} of
 * #177. They are written from the issue, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}: the data is inserted with SQL and every check goes through HTTP, and {@link
 * ApiContract} validates every response against the contract.
 *
 * <p>Each test runs inside a transaction that is rolled back. Other incidents may exist in the
 * database, and the groups without category or assignment cannot be narrowed by them, so every
 * query is narrowed to a reporting window that only the incidents of the test use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentStatisticsApiIT {

    private static final String STATISTICS = "/api/v1/incident-statistics";
    private static final String INCIDENTS = "/api/v1/incidents";

    private static final Instant REPORTED_AT = Instant.parse("2037-01-15T00:00:00Z");
    // The window of the test: no other test reports incidents in it.
    private static final String[] WINDOW = {
        "reportedFrom", "2037-01-01T00:00:00Z", "reportedTo", "2037-02-01T00:00:00Z"
    };

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID otherTechnician;
    private UUID analyst;
    // The names are unique per test, so they do not clash with other data, but they keep the order
    // of their prefix. Looked up by prefix.
    private final Map<String, String> names = new HashMap<>();

    @BeforeEach
    void createUsers() {
        citizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        otherTechnician = user(Role.TECHNICIAN);
        analyst = user(Role.ANALYST);
    }

    // T1: the total is what the inbox lists for the same filters, and every list adds up to it.
    @Test
    void theTotalIsTheInboxTotalAndEveryListAddsUpToIt() throws Exception {
        UUID lighting = category("Lighting");
        UUID roads = category("Roads");
        UUID works = department("Public works");
        incident("REPORTED", null, lighting);
        incident("VALIDATED", "HIGH", lighting);
        incident("VALIDATED", "LOW", null);
        assign(incident("ASSIGNED", "MEDIUM", roads), works, technician, true);
        assign(incident("CLOSED", "LOW", roads), works, null, false);

        for (String[] filters :
                new String[][] {
                    {},
                    {"status", "VALIDATED", "status", "ASSIGNED"},
                    {"priority", "LOW", "priority", "HIGH"},
                    {"categoryId", lighting.toString()},
                    {"departmentId", works.toString()}
                }) {
            Map<String, Object> statistics = statistics(operator, with(filters));
            String what = String.join(" ", filters);

            assertThat(total(statistics))
                    .as("total for %s", what)
                    .isEqualTo(inboxTotal(operator, with(filters)));
            for (String list : List.of("byStatus", "byPriority", "byCategory", "byDepartment")) {
                assertThat(sum(statistics, list))
                        .as("%s for %s", list, what)
                        .isEqualTo(total(statistics));
            }
        }
        assertThat(total(statistics(operator, WINDOW))).isEqualTo(5);
    }

    // T2: without a status filter, the eight statuses in the declared order with real zeros.
    @Test
    void withoutAStatusFilterEveryStatusIsListedInDeclaredOrder() throws Exception {
        incident("REPORTED", null, null);
        incident("REPORTED", null, null);
        incident("CLOSED", null, null);

        assertThat(counts(statistics(operator, WINDOW), "byStatus", "status"))
                .containsExactly(
                        "REPORTED=2",
                        "VALIDATED=0",
                        "REJECTED=0",
                        "ASSIGNED=0",
                        "IN_PROGRESS=0",
                        "RESOLVED=0",
                        "REOPENED=0",
                        "CLOSED=1");
    }

    // T2: with a status filter, only the filtered statuses, a real zero included.
    @Test
    void aStatusFilterListsOnlyItsStatusesWithRealZeros() throws Exception {
        incident("REPORTED", null, null);
        incident("REPORTED", null, null);
        incident("CLOSED", null, null);

        Map<String, Object> statistics =
                statistics(operator, with("status", "VALIDATED", "status", "REPORTED"));

        assertThat(counts(statistics, "byStatus", "status"))
                .containsExactly("REPORTED=2", "VALIDATED=0");
        assertThat(total(statistics)).isEqualTo(2);
    }

    // T3: what has no priority, no category or no active assignment is the group null, last.
    @Test
    void incidentsWithoutPriorityCategoryOrAssignmentAreTheNullGroupLast() throws Exception {
        UUID lighting = category("Lighting");
        UUID works = department("Public works");
        assign(incident("ASSIGNED", "HIGH", lighting), works, null, true);
        // Its only assignment is inactive: it has no active assignment.
        assign(incident("VALIDATED", "LOW", lighting), works, null, false);
        incident("REPORTED", null, null);
        incident("REPORTED", null, null);

        Map<String, Object> statistics = statistics(operator, WINDOW);

        assertThat(counts(statistics, "byPriority", "priority"))
                .containsExactly("HIGH=1", "MEDIUM=0", "LOW=1", "null=2");
        assertThat(counts(statistics, "byCategory", "categoryId"))
                .containsExactly(lighting + "=2", "null=2");
        assertThat(counts(statistics, "byDepartment", "departmentId"))
                .containsExactly(works + "=1", "null=3");
        // The null group has a null name too, as the contract says.
        assertThat(lastOf(statistics, "byCategory")).containsEntry("name", null);
        assertThat(lastOf(statistics, "byDepartment")).containsEntry("name", null);
        assertThat(lastOf(statistics, "byPriority")).containsEntry("priority", null);
    }

    // T3: without any incident of the group without category, it is not listed.
    @Test
    void theNullGroupOfCategoryAndDepartmentIsOnlyListedWithIncidents() throws Exception {
        UUID lighting = category("Lighting");
        UUID works = department("Public works");
        assign(incident("ASSIGNED", "HIGH", lighting), works, null, true);

        Map<String, Object> statistics = statistics(operator, WINDOW);

        assertThat(counts(statistics, "byCategory", "categoryId")).containsExactly(lighting + "=1");
        assertThat(counts(statistics, "byDepartment", "departmentId"))
                .containsExactly(works + "=1");
        // The group without priority is a priority, so it is listed with its real zero.
        assertThat(counts(statistics, "byPriority", "priority"))
                .containsExactly("HIGH=1", "MEDIUM=0", "LOW=0", "null=0");
    }

    // T4: category and department groups carry id and name, the largest first and then by name.
    @Test
    void categoriesAndDepartmentsCarryTheirNameLargestFirstThenByName() throws Exception {
        UUID alpha = category("Alpha");
        UUID bravo = category("Bravo");
        UUID zulu = category("Zulu");
        UUID east = department("East");
        UUID west = department("West");
        UUID south = department("South");
        for (int i = 0; i < 3; i++) {
            assign(incident("ASSIGNED", null, zulu), south, null, true);
        }
        assign(incident("ASSIGNED", null, bravo), east, null, true);
        assign(incident("ASSIGNED", null, alpha), west, null, true);

        Map<String, Object> statistics = statistics(operator, WINDOW);

        assertThat(counts(statistics, "byCategory", "categoryId"))
                .containsExactly(zulu + "=3", alpha + "=1", bravo + "=1");
        assertThat(namesOf(statistics, "byCategory"))
                .containsExactly(name("Zulu"), name("Alpha"), name("Bravo"));
        assertThat(counts(statistics, "byDepartment", "departmentId"))
                .containsExactly(south + "=3", east + "=1", west + "=1");
        assertThat(namesOf(statistics, "byDepartment"))
                .containsExactly(name("South"), name("East"), name("West"));
    }

    // With a priority filter, only those priorities are listed and not the group without one.
    @Test
    void aPriorityFilterListsOnlyItsPrioritiesAndNoNullGroup() throws Exception {
        incident("REPORTED", "HIGH", null);
        incident("REPORTED", "LOW", null);
        incident("REPORTED", null, null);

        Map<String, Object> statistics =
                statistics(operator, with("priority", "MEDIUM", "priority", "HIGH"));

        assertThat(counts(statistics, "byPriority", "priority"))
                .containsExactly("HIGH=1", "MEDIUM=0");
        assertThat(total(statistics)).isEqualTo(1);
    }

    // T12: a technician only counts their active assignments.
    @Test
    void technicianOnlyCountsTheirActiveAssignments() throws Exception {
        UUID works = department("Public works");
        UUID other = department("Other");
        assign(incident("ASSIGNED", "LOW", null), works, technician, true);
        UUID reassigned = incident("ASSIGNED", "LOW", null);
        assign(reassigned, works, technician, false);
        assign(reassigned, other, otherTechnician, true);
        assign(incident("ASSIGNED", "HIGH", null), other, otherTechnician, true);
        incident("VALIDATED", "LOW", null);

        Map<String, Object> mine = statistics(technician, WINDOW);

        assertThat(total(mine)).isEqualTo(1);
        assertThat(counts(mine, "byDepartment", "departmentId")).containsExactly(works + "=1");
        assertThat(counts(mine, "byPriority", "priority"))
                .containsExactly("HIGH=0", "MEDIUM=0", "LOW=1", "null=0");
        assertThat(total(statistics(otherTechnician, WINDOW))).isEqualTo(2);
        assertThat(total(statistics(operator, WINDOW))).isEqualTo(4);
        assertThat(total(statistics(administrator, WINDOW))).isEqualTo(4);
    }

    // T13: citizens and analysts have no dashboard.
    @Test
    void citizensAndAnalystsAreForbidden() throws Exception {
        for (UUID caller : List.of(citizen, analyst)) {
            var response = perform(STATISTICS, caller).andReturn().getResponse();
            assertThat(response.getStatus()).as("status for %s", roleOf(caller)).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("application/problem+json");
        }
    }

    // T14: without a token.
    @Test
    void withoutATokenIsUnauthorized() throws Exception {
        mvc.perform(get(STATISTICS)).andExpect(status().isUnauthorized());
    }

    // T15: an unknown or wrong parameter answers 400, like the list and the map.
    @Test
    void anUnknownEmptyOrWrongParameterIsMalformed() throws Exception {
        String[][] queries = {
            {"colour", "red"},
            {"page", "0"},
            {"minLatitude", "36.70"},
            {"categoryId", ""},
            {"status", ""},
            {"status", "FOO"},
            {"priority", "URGENT"},
            {"categoryId", "not-a-uuid"},
            {"departmentId", "not-a-uuid"},
            {"reportedFrom", "2037-01-01T00:00:00"},
            {"reportedFrom", "2037-01-02T00:00:00Z", "reportedTo", "2037-01-01T00:00:00Z"}
        };
        for (String[] query : queries) {
            var response = perform(STATISTICS, operator, query).andReturn().getResponse();
            String what = String.join(" ", query);
            assertThat(response.getStatus()).as("status for %s", what).isEqualTo(400);
            assertThat(response.getContentType())
                    .as("type for %s", what)
                    .startsWith("application/problem+json");
            assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                    .as("code for %s", what)
                    .isEqualTo("MALFORMED_REQUEST");
        }
    }

    // T16: filters that match nothing are a 200 with real zeros and no category or department.
    @Test
    void filtersThatMatchNothingGiveZerosAndNoCategoryOrDepartment() throws Exception {
        incident("REPORTED", "HIGH", category("Lighting"));

        Map<String, Object> statistics = statistics(operator, with("status", "CLOSED"));

        assertThat(total(statistics)).isZero();
        assertThat(counts(statistics, "byStatus", "status")).containsExactly("CLOSED=0");
        assertThat(counts(statistics, "byPriority", "priority"))
                .containsExactly("HIGH=0", "MEDIUM=0", "LOW=0", "null=0");
        assertThat((List<?>) statistics.get("byCategory")).isEmpty();
        assertThat((List<?>) statistics.get("byDepartment")).isEmpty();
    }

    // T20: the map stops at 1,000 incidents, the statistics count every one.
    @Test
    void moreIncidentsThanTheMapCapAreAllCounted() throws Exception {
        int count = IncidentQueryService.MAX_LOCATIONS + 1;
        jdbc.update(
                "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                        + " updated_at)"
                        // A parameter in a SELECT has no type to infer, so each one is cast.
                        + " SELECT CAST(? AS uuid), 'Broken streetlight', 'The lamp is off.',"
                        + " 'REPORTED', CAST(? AS timestamptz), CAST(? AS timestamptz)"
                        + " FROM generate_series(1, CAST(? AS integer))",
                citizen,
                Timestamp.from(REPORTED_AT),
                Timestamp.from(REPORTED_AT),
                count);

        Map<String, Object> statistics = statistics(operator, WINDOW);

        assertThat(total(statistics)).isEqualTo(count);
        assertThat(sum(statistics, "byStatus")).isEqualTo(count);
        assertThat(sum(statistics, "byPriority")).isEqualTo(count);
        assertThat(sum(statistics, "byCategory")).isEqualTo(count);
        assertThat(sum(statistics, "byDepartment")).isEqualTo(count);
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

    private UUID category(String prefix) {
        String name = prefix + " " + UUID.randomUUID();
        names.put(prefix, name);
        return jdbc.queryForObject(
                "INSERT INTO category (name) VALUES (?) RETURNING id", UUID.class, name);
    }

    private UUID department(String prefix) {
        String name = prefix + " " + UUID.randomUUID();
        names.put(prefix, name);
        return jdbc.queryForObject(
                "INSERT INTO department (name) VALUES (?) RETURNING id", UUID.class, name);
    }

    private String name(String prefix) {
        return names.get(prefix);
    }

    /** An incident in the window of the test; {@code priority} and {@code category} may be null. */
    private UUID incident(String status, String priority, UUID category) {
        return jdbc.queryForObject(
                "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                        + " priority, priority_reason, priority_source, reported_at, updated_at)"
                        + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, ?, ?, ?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                citizen,
                category,
                status,
                priority,
                priority == null ? null : "Set by the test",
                priority == null ? null : "MANUAL",
                Timestamp.from(REPORTED_AT),
                Timestamp.from(REPORTED_AT));
    }

    private void assign(UUID incident, UUID department, UUID assignedTechnician, boolean active) {
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?, ?)",
                incident,
                department,
                assignedTechnician,
                Timestamp.from(REPORTED_AT),
                active);
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    /** The reporting window of the test followed by more query parameters. */
    private static String[] with(String... more) {
        String[] all = new String[WINDOW.length + more.length];
        System.arraycopy(WINDOW, 0, all, 0, WINDOW.length);
        System.arraycopy(more, 0, all, WINDOW.length, more.length);
        return all;
    }

    /** {@code GET path} as {@code caller}, with the query parameters as name, value pairs. */
    private ResultActions perform(String path, UUID caller, String... nameValuePairs)
            throws Exception {
        MockHttpServletRequestBuilder request =
                get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller)));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mvc.perform(request);
    }

    private Map<String, Object> statistics(UUID caller, String... nameValuePairs) throws Exception {
        String body =
                perform(STATISTICS, caller, nameValuePairs)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(body, "$");
    }

    private long inboxTotal(UUID caller, String... nameValuePairs) throws Exception {
        String body =
                perform(INCIDENTS, caller, nameValuePairs)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return ((Number) JsonPath.read(body, "$.page.totalElements")).longValue();
    }

    private static long total(Map<String, Object> statistics) {
        return ((Number) statistics.get("total")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> statistics, String name) {
        return (List<Map<String, Object>>) statistics.get(name);
    }

    private static long sum(Map<String, Object> statistics, String name) {
        return list(statistics, name).stream()
                .mapToLong(group -> ((Number) group.get("count")).longValue())
                .sum();
    }

    /** Each group of a list as {@code key=count}, in the order of the response. */
    private static List<String> counts(Map<String, Object> statistics, String name, String key) {
        return list(statistics, name).stream()
                .map(group -> group.get(key) + "=" + group.get("count"))
                .toList();
    }

    private static List<Object> namesOf(Map<String, Object> statistics, String name) {
        return list(statistics, name).stream().map(group -> group.get("name")).toList();
    }

    private static Map<String, Object> lastOf(Map<String, Object> statistics, String name) {
        List<Map<String, Object>> groups = list(statistics, name);
        return groups.get(groups.size() - 1);
    }
}
