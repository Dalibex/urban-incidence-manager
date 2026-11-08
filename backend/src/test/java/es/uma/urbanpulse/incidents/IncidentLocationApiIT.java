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
 * Acceptance tests of #76 (T4, T5, T7 to T9, T15 and T16) for {@code listIncidentLocations} of
 * #195. T6 is covered by the contract validation of every response here ({@link ApiContract}) and
 * by the {@code contract} CI job, which checks that the generated client is up to date.
 *
 * <p>They are written from the issues, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}: the data is inserted with SQL and every check goes through HTTP.
 *
 * <p>Each test runs inside a transaction that is rolled back. Other incidents may exist in the
 * database, so every staff query is narrowed with a category created by the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentLocationApiIT {

    private static final String LOCATIONS = "/api/v1/incident-locations";
    private static final String INCIDENTS = "/api/v1/incidents";
    private static final int CAP = 1000;

    private static final Instant JAN_1 = Instant.parse("2031-01-01T00:00:00Z");

    // Inside the city-centre box of the tests: the town hall. Outside: Torremolinos.
    private static final double TOWN_HALL_LATITUDE = 36.7190;
    private static final double TOWN_HALL_LONGITUDE = -4.4176;
    private static final double TORREMOLINOS_LATITUDE = 36.6219;
    private static final double TORREMOLINOS_LONGITUDE = -4.4997;
    private static final String[] CITY_CENTRE = {
        "minLatitude",
        "36.70",
        "minLongitude",
        "-4.44",
        "maxLatitude",
        "36.74",
        "maxLongitude",
        "-4.40"
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

    // T4: the same filters and bounding box give the same incidents as the inbox.
    @Test
    void filtersAndBoxGiveTheSameIncidentsAsTheInbox() throws Exception {
        UUID highInside = incident("VALIDATED", "HIGH", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        UUID reportedInside = incident("REPORTED", null, TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        incident("VALIDATED", "LOW", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        UUID highOutside =
                incident("VALIDATED", "HIGH", TORREMOLINOS_LATITUDE, TORREMOLINOS_LONGITUDE);

        String[] filters = {
            "categoryId", category.toString(), "status", "VALIDATED", "status", "REPORTED"
        };
        List<String> inbox = inboxIds(operator, filters);
        assertThat(inbox).contains(highInside.toString(), highOutside.toString());

        assertThat(locationIds(operator, concat(filters, "priority", "HIGH")))
                .containsExactlyInAnyOrder(highInside.toString(), highOutside.toString())
                .containsExactlyInAnyOrderElementsOf(
                        inboxIds(operator, concat(filters, "priority", "HIGH")));
        // The box narrows the inbox result to the incidents inside it.
        assertThat(locationIds(operator, concat(filters, CITY_CENTRE)))
                .containsExactlyInAnyOrderElementsOf(
                        inbox.stream().filter(id -> !id.equals(highOutside.toString())).toList())
                .contains(highInside.toString(), reportedInside.toString());
    }

    // T4: each item carries what the map needs, with latitude and longitude in their place.
    @Test
    void eachItemHasTheFieldsOfThePositionNotSwapped() throws Exception {
        UUID id = incident("VALIDATED", "MEDIUM", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);

        locations(operator, "categoryId", category.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].status").value("VALIDATED"))
                .andExpect(jsonPath("$.content[0].priority").value("MEDIUM"))
                .andExpect(jsonPath("$.content[0].latitude").value(TOWN_HALL_LATITUDE))
                .andExpect(jsonPath("$.content[0].longitude").value(TOWN_HALL_LONGITUDE));
    }

    // T4: an operator and an administrator see every incident.
    @Test
    void operatorsAndAdministratorsSeeEveryIncident() throws Exception {
        UUID unassigned = incident("REPORTED", null, TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        UUID assigned = incident("ASSIGNED", "LOW", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        assign(assigned, technician, true);

        for (UUID staff : List.of(operator, administrator)) {
            assertThat(locationIds(staff, "categoryId", category.toString()))
                    .containsExactlyInAnyOrder(unassigned.toString(), assigned.toString());
        }
    }

    // T4: no match is an empty list, not an error.
    @Test
    void noMatchIsAnEmptyList() throws Exception {
        incident("REPORTED", null, TORREMOLINOS_LATITUDE, TORREMOLINOS_LONGITUDE);

        locations(operator, concat(new String[] {"categoryId", category.toString()}, CITY_CENTRE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false));
    }

    // T5: citizens and analysts have no map.
    @Test
    void citizensAndAnalystsAreForbidden() throws Exception {
        for (UUID caller : List.of(citizen, analyst)) {
            var response = locations(caller).andReturn().getResponse();
            assertThat(response.getStatus()).as("status for %s", roleOf(caller)).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("application/problem+json");
        }
    }

    // T7: a technician only gets their active assignments.
    @Test
    void technicianOnlyGetsTheirActiveAssignments() throws Exception {
        UUID active = incident("ASSIGNED", "LOW", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        assign(active, technician, true);
        UUID reassigned = incident("ASSIGNED", "LOW", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        assign(reassigned, technician, false);
        assign(reassigned, otherTechnician, true);
        incident("VALIDATED", "LOW", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);

        assertThat(locationIds(technician, "categoryId", category.toString()))
                .containsExactly(active.toString());
        assertThat(locationIds(otherTechnician, "categoryId", category.toString()))
                .containsExactly(reassigned.toString());
    }

    // T8: a box with a minimum over its maximum, out of range, or incomplete.
    @Test
    void anInvalidBoxIsMalformed() throws Exception {
        // South over north and west over east.
        expectMalformed(box("36.74", "-4.44", "36.70", "-4.40"));
        expectMalformed(box("36.70", "-4.40", "36.74", "-4.44"));
        // Out of range.
        expectMalformed(box("-90.5", "-4.44", "36.74", "-4.40"));
        expectMalformed(box("36.70", "-4.44", "90.5", "-4.40"));
        expectMalformed(box("36.70", "-180.5", "36.74", "-4.40"));
        expectMalformed(box("36.70", "-4.44", "36.74", "180.5"));
        // Not a number.
        expectMalformed(box("NaN", "-4.44", "36.74", "-4.40"));
        expectMalformed(box("north", "-4.44", "36.74", "-4.40"));
        // Only some of the four limits.
        expectMalformed("minLatitude", "36.70");
        expectMalformed("minLatitude", "36.70", "minLongitude", "-4.44", "maxLatitude", "36.74");
    }

    // T8: the edges of the box are included.
    @Test
    void theEdgesOfTheBoxAreIncluded() throws Exception {
        UUID corner = incident("REPORTED", null, 36.74, -4.40);

        assertThat(
                        locationIds(
                                operator,
                                concat(
                                        new String[] {"categoryId", category.toString()},
                                        CITY_CENTRE)))
                .containsExactly(corner.toString());
    }

    // T9: over the cap it is flagged as truncated and the most relevant come first.
    @Test
    void overTheCapItIsTruncatedWithTheMostRelevantFirst() throws Exception {
        // CAP incidents without priority, newer than the two below.
        manyIncidents(CAP, JAN_1.plusSeconds(3600));
        UUID oldHigh = incident("VALIDATED", "HIGH", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);
        UUID oldMedium = incident("VALIDATED", "MEDIUM", TOWN_HALL_LATITUDE, TOWN_HALL_LONGITUDE);

        String body =
                locations(operator, "categoryId", category.toString())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.truncated").value(true))
                        .andExpect(jsonPath("$.content.length()").value(CAP))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<String> ids = JsonPath.read(body, "$.content[*].id");
        assertThat(ids.subList(0, 2)).containsExactly(oldHigh.toString(), oldMedium.toString());
    }

    // T9: exactly the cap is not truncated.
    @Test
    void exactlyTheCapIsNotTruncated() throws Exception {
        manyIncidents(CAP, JAN_1);

        locations(operator, "categoryId", category.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.content.length()").value(CAP));
    }

    // T15: without a token.
    @Test
    void withoutATokenIsUnauthorized() throws Exception {
        mvc.perform(get(LOCATIONS)).andExpect(status().isUnauthorized());
    }

    // T16: an unknown or wrong filter answers 400, the same as the list.
    @Test
    void anUnknownOrWrongFilterIsMalformed() throws Exception {
        String[][] queries = {
            {"colour", "red"},
            {"bbox", "36.70,-4.44,36.74,-4.40"},
            {"page", "0"},
            {"categoryId", ""},
            {"status", "OPEN"},
            {"priority", "URGENT"},
            {"categoryId", "not-a-uuid"},
            {"departmentId", "not-a-uuid"},
            {"reportedFrom", "2031-01-01T00:00:00"},
            {"reportedFrom", "2031-01-02T00:00:00Z", "reportedTo", "2031-01-01T00:00:00Z"}
        };
        for (String[] query : queries) {
            expectMalformed(query);
            // The list answers the same to the same query.
            assertThat(inbox(operator, query).andReturn().getResponse().getStatus())
                    .as("inbox status for %s", String.join(" ", query))
                    .isEqualTo(query[0].equals("page") ? 200 : 400);
        }
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

    /** An incident of the test's category at a position; {@code priority} may be null. */
    private UUID incident(String status, String priority, double latitude, double longitude) {
        UUID incident =
                jdbc.queryForObject(
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
                        Timestamp.from(JAN_1),
                        Timestamp.from(JAN_1));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)",
                incident,
                longitude,
                latitude);
        return incident;
    }

    /** {@code count} reported incidents without priority, one second apart from {@code from}. */
    private void manyIncidents(int count, Instant from) {
        jdbc.update(
                "WITH created AS ("
                        + " INSERT INTO incident (reporter_id, category_id, title, description, status,"
                        + " reported_at, updated_at)"
                        // A parameter in a SELECT has no type to infer, so each one is cast.
                        + " SELECT CAST(? AS uuid), CAST(? AS uuid), 'Broken streetlight',"
                        + " 'The lamp is off.', 'REPORTED',"
                        + " CAST(? AS timestamptz) + n * interval '1 second',"
                        + " CAST(? AS timestamptz) + n * interval '1 second'"
                        + " FROM generate_series(1, CAST(? AS integer)) AS n RETURNING id)"
                        + " INSERT INTO location (incident_id, coordinates)"
                        + " SELECT id, ST_SetSRID(ST_MakePoint(-4.4176, 36.7190), 4326)::geography"
                        + " FROM created",
                citizen,
                category,
                Timestamp.from(from),
                Timestamp.from(from),
                count);
    }

    private void assign(UUID incident, UUID assignedTechnician, boolean active) {
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

    private ResultActions locations(UUID caller, String... nameValuePairs) throws Exception {
        return perform(LOCATIONS, caller, nameValuePairs);
    }

    private ResultActions inbox(UUID caller, String... nameValuePairs) throws Exception {
        return perform(INCIDENTS, caller, nameValuePairs);
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

    private List<String> locationIds(UUID caller, String... nameValuePairs) throws Exception {
        String body =
                locations(caller, nameValuePairs)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(body, "$.content[*].id");
    }

    /** The ids of the first inbox page, which holds all the incidents of a test (size 100). */
    private List<String> inboxIds(UUID caller, String... nameValuePairs) throws Exception {
        String body =
                inbox(caller, concat(nameValuePairs, "size", "100"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(body, "$.content[*].id");
    }

    /** {@code GET /incident-locations} as the operator answers 400 {@code MALFORMED_REQUEST}. */
    private void expectMalformed(String... query) throws Exception {
        var response = locations(operator, query).andReturn().getResponse();
        String what = String.join(" ", query);
        assertThat(response.getStatus()).as("status for %s", what).isEqualTo(400);
        assertThat(response.getContentType())
                .as("type for %s", what)
                .startsWith("application/problem+json");
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .as("code for %s", what)
                .isEqualTo("MALFORMED_REQUEST");
    }

    private static String[] box(
            String minLatitude, String minLongitude, String maxLatitude, String maxLongitude) {
        return new String[] {
            "minLatitude", minLatitude,
            "minLongitude", minLongitude,
            "maxLatitude", maxLatitude,
            "maxLongitude", maxLongitude
        };
    }

    private static String[] concat(String[] first, String... second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
