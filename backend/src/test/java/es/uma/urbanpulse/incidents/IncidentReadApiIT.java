package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance tests of #103 for the read endpoints of #100: {@code listIncidents}, {@code
 * getIncident} and {@code listIncidentStatusChanges}.
 *
 * <p>They are written from the issue, the contract ({@code docs/api/openapi.yml}), {@code
 * docs/api.md} and ADR-0020, not from the implementation: the data is inserted with SQL on the
 * Flyway schema and every check goes through HTTP. Every response is also validated against the
 * contract ({@link ApiContract}).
 *
 * <p>Each test creates its own users and incidents inside a transaction that is rolled back, so the
 * tests never see each other's data. Other incidents may exist in the database (from other tests or
 * migrations): the staff listings are checked with the incidents of the test, never with a total.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentReadApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-11-03T09:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-11-04T09:00:00Z");

    private static final String INFORMATION_MESSAGE = "Which streetlight exactly? Send a photo.";
    private static final String RESOLUTION_NOTES = "Replaced the lamp; internal ticket 4411.";
    private static final String REOPENING_REASON = "The lamp failed again during the inspection.";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private UUID citizenA;
    private UUID citizenB;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID analyst;
    private UUID department;
    private UUID category;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizenA = user(Role.CITIZEN);
        citizenB = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        analyst = user(Role.ANALYST);
        department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES ('Street lighting') RETURNING id",
                        UUID.class);
        category =
                jdbc.queryForObject(
                        "INSERT INTO category (name) VALUES ('Lighting') RETURNING id", UUID.class);
    }

    // T2
    @Test
    void citizenListsOnlyTheirIncidentsWithThePageMetadata() throws Exception {
        UUID first = incident(citizenA, "REPORTED", MONDAY);
        UUID second = incident(citizenA, "VALIDATED", TUESDAY);
        incident(citizenB, "REPORTED", WEDNESDAY);

        String body =
                list(citizenA, "")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(ids(body)).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat((Integer) JsonPath.read(body, "$.page.number")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.page.size")).isEqualTo(20);
        assertThat(((Number) JsonPath.read(body, "$.page.totalElements")).longValue()).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body, "$.page.totalPages")).isEqualTo(1);
    }

    // T2: the default order is reportedAt, newest first.
    @Test
    void listIsNewestFirstByDefault() throws Exception {
        UUID monday = incident(citizenA, "REPORTED", MONDAY);
        UUID wednesday = incident(citizenA, "REPORTED", WEDNESDAY);
        UUID tuesday = incident(citizenA, "REPORTED", TUESDAY);

        String body =
                list(citizenA, "")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(ids(body))
                .containsExactly(wednesday.toString(), tuesday.toString(), monday.toString());
    }

    // T3
    @Test
    void technicianListsOnlyIncidentsActivelyAssignedToThem() throws Exception {
        UUID active = incident(citizenA, "ASSIGNED", MONDAY);
        UUID reassigned = incident(citizenA, "ASSIGNED", TUESDAY);
        assign(active, technician, true);
        assign(reassigned, technician, false);
        assign(reassigned, user(Role.TECHNICIAN), true);

        String body =
                list(technician, "")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(ids(body)).containsExactly(active.toString());
    }

    // T4
    @Test
    void operatorsAndAdministratorsListEveryIncident() throws Exception {
        UUID ofA = incident(citizenA, "REPORTED", MONDAY);
        UUID ofB = incident(citizenB, "VALIDATED", TUESDAY);

        for (UUID staff : new UUID[] {operator, administrator}) {
            String body =
                    list(staff, "?size=100")
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(ids(body)).contains(ofA.toString(), ofB.toString());
        }
    }

    // T5: someone else's incident answers exactly like an id that does not exist.
    @Test
    void someoneElsesIncidentIsNotFoundLikeAnUnknownOne() throws Exception {
        UUID ofB = incident(citizenB, "ASSIGNED", MONDAY);
        assign(ofB, user(Role.TECHNICIAN), true);
        UUID unknown = UUID.randomUUID();

        for (String suffix : new String[] {"", "/status-changes"}) {
            for (UUID caller : new UUID[] {citizenA, technician}) {
                String hidden = notFound(caller, INCIDENTS + "/" + ofB + suffix);
                String missing = notFound(caller, INCIDENTS + "/" + unknown + suffix);
                assertThat(withoutRequestedId(hidden, ofB))
                        .isEqualTo(withoutRequestedId(missing, unknown));
            }
        }
    }

    // T5: a technician whose assignment is no longer active loses access.
    @Test
    void technicianWithAnInactiveAssignmentGetsNotFound() throws Exception {
        UUID incident = incident(citizenA, "ASSIGNED", MONDAY);
        assign(incident, technician, false);

        notFound(technician, INCIDENTS + "/" + incident);
    }

    // T6
    @Test
    void analystIsForbiddenOnTheThreeEndpoints() throws Exception {
        UUID incident = incident(citizenA, "REPORTED", MONDAY);

        for (String path : readPaths(incident)) {
            mvc.perform(as(analyst, Role.ANALYST, get(path)))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    // T7
    @Test
    void theThreeEndpointsNeedAToken() throws Exception {
        UUID incident = incident(citizenA, "REPORTED", MONDAY);

        for (String path : readPaths(incident)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // T8: ADR-0020, "Citizen sees" column.
    @Test
    void citizenGetsOnlyTheVisibleHistoryWithTheReasonsMeantForThem() throws Exception {
        UUID incident = incidentWithFullHistory(citizenA);

        String body =
                mvc.perform(
                                as(
                                        citizenA,
                                        Role.CITIZEN,
                                        get(INCIDENTS + "/" + incident + "/status-changes")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<Map<String, Object>> entries = JsonPath.read(body, "$");
        assertThat(entries)
                .extracting(entry -> entry.get("newStatus"))
                .containsExactly(
                        "REPORTED",
                        "REPORTED",
                        "VALIDATED",
                        "ASSIGNED",
                        "IN_PROGRESS",
                        "RESOLVED",
                        "REOPENED");
        assertThat(entries.get(1).get("reason")).isEqualTo(INFORMATION_MESSAGE);
        for (Map<String, Object> entry : entries) {
            assertThat(entry).doesNotContainKeys("actor", "visibleToCitizen");
            if (entry != entries.get(1)) {
                assertThat(entry.get("reason")).as("reason of %s", entry.get("newStatus")).isNull();
            }
        }
        assertThat(body)
                .doesNotContain(RESOLUTION_NOTES)
                .doesNotContain(REOPENING_REASON)
                .doesNotContain("internal note");
    }

    // T8: a rejection shows its reason to the citizen.
    @Test
    void citizenSeesTheReasonOfARejection() throws Exception {
        UUID incident = incident(citizenA, "REJECTED", TUESDAY);
        history(incident, citizenA, MONDAY, null, "REPORTED", null, true);
        history(
                incident,
                operator,
                TUESDAY,
                "REPORTED",
                "REJECTED",
                "Duplicate of an open report.",
                true);

        mvc.perform(as(citizenA, Role.CITIZEN, get(INCIDENTS + "/" + incident + "/status-changes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].newStatus").value("REJECTED"))
                .andExpect(jsonPath("$[1].reason").value("Duplicate of an open report."));
    }

    // T8: the citizen's list and detail leave out the staff-only priority.
    @Test
    void citizenNeverReceivesThePriority() throws Exception {
        UUID incident = incident(citizenA, "VALIDATED", MONDAY);

        list(citizenA, "").andExpect(jsonPath("$.content[0].priority").doesNotExist());
        mvc.perform(as(citizenA, Role.CITIZEN, get(INCIDENTS + "/" + incident)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").doesNotExist());
    }

    // T9
    @Test
    void staffGetEveryHistoryEntryWithItsReasonAndActor() throws Exception {
        UUID incident = incidentWithFullHistory(citizenA);

        String body =
                mvc.perform(
                                as(
                                        operator,
                                        Role.MUNICIPAL_OPERATOR,
                                        get(INCIDENTS + "/" + incident + "/status-changes")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<Map<String, Object>> entries = JsonPath.read(body, "$");
        assertThat(entries).hasSize(8);
        assertThat(body)
                .contains(INFORMATION_MESSAGE, RESOLUTION_NOTES, REOPENING_REASON, "internal note");
        assertThat(entries)
                .allSatisfy(entry -> assertThat(entry).containsKeys("actor", "visibleToCitizen"));
        assertThat(entries).extracting(entry -> entry.get("visibleToCitizen")).contains(false);
    }

    // T9: staff see the priority.
    @Test
    void staffReceiveThePriority() throws Exception {
        UUID incident = incident(citizenA, "VALIDATED", MONDAY);

        mvc.perform(as(operator, Role.MUNICIPAL_OPERATOR, get(INCIDENTS + "/" + incident)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    // T10
    @Test
    void statusFilterKeepsOnlyThoseStatuses() throws Exception {
        UUID reported = incident(citizenA, "REPORTED", MONDAY);
        UUID validated = incident(citizenA, "VALIDATED", TUESDAY);
        UUID rejected = incident(citizenA, "REJECTED", WEDNESDAY);

        String onlyReported =
                list(citizenA, "?status=REPORTED")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(ids(onlyReported)).containsExactly(reported.toString());

        String two =
                list(citizenA, "?status=REPORTED&status=REJECTED")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(ids(two)).containsExactlyInAnyOrder(reported.toString(), rejected.toString());
        assertThat(ids(two)).doesNotContain(validated.toString());
    }

    // T11
    @Test
    void invalidPagingIsABadRequest() throws Exception {
        for (String query :
                new String[] {
                    "?size=101", "?sort=unknown,asc", "?size=0", "?page=-1", "?status=OPEN"
                }) {
            expectMalformed(list(citizenA, query), query);
        }
    }

    // T11: an identifier that is not a UUID is a malformed request too (docs/api.md).
    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        expectMalformed(
                mvc.perform(as(citizenA, Role.CITIZEN, get(INCIDENTS + "/not-a-uuid"))), "id");
    }

    // T12
    @Test
    void tiesAreBrokenByIdSoPagesNeitherRepeatNorSkip() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(incident(citizenA, "REPORTED", MONDAY).toString());
        }

        List<String> firstRun = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            firstRun.addAll(
                    ids(
                            list(citizenA, "?size=2&page=" + page)
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString()));
        }
        List<String> secondRun = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            secondRun.addAll(
                    ids(
                            list(citizenA, "?size=2&page=" + page)
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString()));
        }

        assertThat(firstRun).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();
        assertThat(secondRun).isEqualTo(firstRun);
        // PostgreSQL orders uuid byte by byte, which is the text order of their lower-case form
        // (UUID.compareTo in Java compares signed numbers and gives another order).
        List<String> byId = created.stream().sorted().toList();
        assertThat(firstRun).isIn(byId, byId.reversed());
    }

    // T13
    @Test
    void listingAPageRunsTheSameNumberOfQueriesWhateverItsSize() throws Exception {
        for (int i = 0; i < 21; i++) {
            incident(citizenA, "VALIDATED", MONDAY.plusSeconds(i), newCategory());
        }
        for (int i = 0; i < 3; i++) {
            incident(citizenB, "VALIDATED", MONDAY.plusSeconds(i), newCategory());
        }

        // Every incident has its own category, so loading them one by one would show as extra
        // queries instead of being served from the persistence context.
        // Both pages are full and have more incidents after them, so both need the total count:
        // only the number of incidents on the page changes.
        long small = statementsFor(citizenB, 2);
        long large = statementsFor(citizenA, 20);

        assertThat(large).as("statements for a page of 20 vs a page of 2").isEqualTo(small);
    }

    // #116: the location is the inverse side of a one-to-one, which Hibernate always loads; the
    // entity graph of findOne joins it, so the detail does not need a second query for it.
    @Test
    void theDetailReadsTheIncidentWithItsCategoryAndLocationInOneQuery() throws Exception {
        UUID incident = incident(citizenA, "VALIDATED", MONDAY, newCategory());

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(as(citizenA, Role.CITIZEN, get(INCIDENTS + "/" + incident)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").isNotEmpty())
                .andExpect(jsonPath("$.location.latitude").isNumber());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    // T14: every response of this class is checked against the contract by ApiContract (#111);
    // this test only makes sure the three endpoints are called by a citizen and by staff, whose
    // responses use different properties.
    @Test
    void responsesOfCitizensAndStaffFollowTheContract() throws Exception {
        UUID incident = incidentWithFullHistory(citizenA);

        for (UUID caller : new UUID[] {citizenA, operator}) {
            for (String path : readPaths(incident)) {
                mvc.perform(as(caller, roleOf(caller), get(path))).andExpect(status().isOk());
            }
        }
    }

    /**
     * An incident of {@code reporter} with the history of ADR-0020: reported, an information
     * request, validated, assigned, accepted, resolved, an internal note hidden from the citizen (a
     * reassignment) and reopened.
     */
    private UUID incidentWithFullHistory(UUID reporter) {
        UUID incident = incident(reporter, "REOPENED", MONDAY);
        Instant at = MONDAY;
        history(incident, reporter, at, null, "REPORTED", null, true);
        history(
                incident,
                operator,
                at = at.plusSeconds(60),
                "REPORTED",
                "REPORTED",
                INFORMATION_MESSAGE,
                true);
        history(
                incident,
                operator,
                at = at.plusSeconds(60),
                "REPORTED",
                "VALIDATED",
                "Category and priority set.",
                true);
        history(
                incident,
                operator,
                at = at.plusSeconds(60),
                "VALIDATED",
                "ASSIGNED",
                "Assigned to the night shift.",
                true);
        history(
                incident,
                operator,
                at = at.plusSeconds(60),
                "ASSIGNED",
                "ASSIGNED",
                "internal note: reassigned",
                false);
        history(
                incident,
                technician,
                at = at.plusSeconds(60),
                "ASSIGNED",
                "IN_PROGRESS",
                null,
                true);
        history(
                incident,
                technician,
                at = at.plusSeconds(60),
                "IN_PROGRESS",
                "RESOLVED",
                RESOLUTION_NOTES,
                true);
        history(
                incident,
                operator,
                at.plusSeconds(60),
                "RESOLVED",
                "REOPENED",
                REOPENING_REASON,
                true);
        return incident;
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, '{bcrypt}not-used', ?)"
                        + " RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    private UUID incident(UUID reporter, String status, Instant reportedAt) {
        return incident(reporter, status, reportedAt, category);
    }

    private UUID newCategory() {
        return jdbc.queryForObject(
                "INSERT INTO category (name) VALUES (?) RETURNING id",
                UUID.class,
                "Category " + UUID.randomUUID());
    }

    private UUID incident(UUID reporter, String status, Instant reportedAt, UUID categoryId) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status, priority,"
                                + " priority_reason, priority_source, reported_at, updated_at)"
                                + " VALUES (?, ?, 'Broken streetlight', 'The lamp is off.', ?, 'HIGH',"
                                + " 'Dark crossing', 'MANUAL', ?, ?) RETURNING id",
                        UUID.class,
                        reporter,
                        categoryId,
                        status,
                        Timestamp.from(reportedAt),
                        Timestamp.from(reportedAt));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
        return incident;
    }

    private void assign(UUID incident, UUID assignedTechnician, boolean active) {
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at, is_active)"
                        + " VALUES (?, ?, ?, ?, ?)",
                incident,
                department,
                assignedTechnician,
                Timestamp.from(MONDAY),
                active);
    }

    private void history(
            UUID incident,
            UUID actor,
            Instant instant,
            String previous,
            String next,
            String reason,
            boolean visibleToCitizen) {
        jdbc.update(
                "INSERT INTO status_change (incident_id, actor_id, instant, previous_status, new_status,"
                        + " reason, visible_to_citizen) VALUES (?, ?, ?, ?, ?, ?, ?)",
                incident,
                actor,
                Timestamp.from(instant),
                previous,
                next,
                reason,
                visibleToCitizen);
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private MockHttpServletRequestBuilder as(
            UUID userId, Role role, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId, role));
    }

    private ResultActions list(UUID userId, String query) throws Exception {
        return list(userId, roleOf(userId), query);
    }

    private ResultActions list(UUID userId, Role role, String query) throws Exception {
        return mvc.perform(as(userId, role, get(INCIDENTS + query)));
    }

    private String notFound(UUID caller, String path) throws Exception {
        return mvc.perform(as(caller, roleOf(caller), get(path)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static void expectMalformed(ResultActions result, String what) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(what).isNotBlank();
    }

    private long statementsFor(UUID citizen, int pageSize) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        list(citizen, "?size=" + pageSize)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(pageSize));
        return statistics.getPrepareStatementCount();
    }

    private String[] readPaths(UUID incident) {
        return new String[] {
            INCIDENTS, INCIDENTS + "/" + incident, INCIDENTS + "/" + incident + "/status-changes"
        };
    }

    private static List<String> ids(String pageBody) {
        return JsonPath.read(pageBody, "$.content[*].id");
    }

    // The requested id, the instance (its path) and the trace id differ by definition; once they
    // are
    // taken out, the answer to a hidden incident must be indistinguishable from an unknown one.
    private static Map<String, Object> withoutRequestedId(String problem, UUID requested) {
        Map<String, Object> body = JsonPath.read(problem, "$");
        body.remove("instance");
        body.remove("traceId");
        body.replaceAll(
                (key, value) ->
                        value instanceof String text
                                ? text.replace(requested.toString(), "{id}")
                                : value);
        return body;
    }
}
