package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Tests of #70 for {@code updateIncident} and {@code listIncidentClassificationChanges} (#47).
 *
 * <p>The data is inserted with SQL on the Flyway schema and every check goes through HTTP; every
 * response is also validated against the contract ({@link ApiContract}). Each test runs in a
 * transaction that is rolled back. T9, two operators at once, needs real commits and is in {@code
 * IncidentClassificationServiceIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentClassificationApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    private static final String REASON = "The street is dark near a school.";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;
    private UUID operator;
    private UUID administrator;
    private UUID technician;
    private UUID lighting;
    private UUID roads;

    @BeforeEach
    void createUsersAndCategories() {
        citizen = user(Role.CITIZEN, "Carmen Citizen");
        operator = user(Role.MUNICIPAL_OPERATOR, "Olga Operator");
        administrator = user(Role.ADMINISTRATOR, "Adrián Admin");
        technician = user(Role.TECHNICIAN, "Tomás Technician");
        lighting = category("Lighting " + UUID.randomUUID(), true);
        roads = category("Roads " + UUID.randomUUID(), true);
    }

    // T3: source MANUAL, the actor and the justification are stored.
    @Test
    void classifyingSetsTheCategoryAndTheManualPriorityWithItsJustification() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        String body =
                classify(
                                operator,
                                incident,
                                Map.of(
                                        "version",
                                        0,
                                        "categoryId",
                                        lighting,
                                        "priority",
                                        "HIGH",
                                        "priorityReason",
                                        REASON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.category.id").value(lighting.toString()))
                        .andExpect(jsonPath("$.priority").value("HIGH"))
                        .andExpect(jsonPath("$.priorityReason").value(REASON))
                        .andExpect(jsonPath("$.status").value("REPORTED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat((Integer) JsonPath.read(body, "$.version")).isPositive();
        var row =
                jdbc.queryForMap(
                        "SELECT category_id, priority, priority_reason, priority_source,"
                                + " priority_setter_id FROM incident WHERE id = ?",
                        incident);
        assertThat(row.get("category_id")).isEqualTo(lighting);
        assertThat(row.get("priority")).isEqualTo("HIGH");
        assertThat(row.get("priority_reason")).isEqualTo(REASON);
        assertThat(row.get("priority_source")).isEqualTo("MANUAL");
        assertThat(row.get("priority_setter_id")).isEqualTo(operator);
        // Not a lifecycle action: no history entry (ADR-0020).
        assertThat(historyEntries(incident)).isZero();
    }

    // T4: each change leaves an entry with the previous and the new values, only those.
    @Test
    void reclassifyingRecordsOnlyTheValuesThatChanged() throws Exception {
        UUID incident = unclassifiedIncident("VALIDATED");
        classify(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                0,
                                "categoryId",
                                lighting,
                                "priority",
                                "LOW",
                                "priorityReason",
                                REASON))
                .andExpect(status().isOk());

        classify(
                        administrator,
                        incident,
                        Map.of(
                                "version",
                                versionOf(incident),
                                "priority",
                                "HIGH",
                                "priorityReason",
                                REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.id").value(lighting.toString()));

        var entries = auditEntries(incident);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("actor_id")).isEqualTo(operator);
        assertThat(entries.get(0).get("before"))
                .isEqualTo("{\"priority\": null, \"categoryId\": null, \"priorityReason\": null}");
        assertThat(entries.get(1).get("actor_id")).isEqualTo(administrator);
        assertThat(entries.get(1).get("before")).isEqualTo("{\"priority\": \"LOW\"}");
        assertThat(entries.get(1).get("after")).isEqualTo("{\"priority\": \"HIGH\"}");
    }

    @Test
    void aRequestThatChangesNothingLeavesTheIncidentAndTheTrailAlone() throws Exception {
        UUID incident = classifiedIncident("ASSIGNED");

        classify(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                0,
                                "categoryId",
                                lighting,
                                "priority",
                                "HIGH",
                                "priorityReason",
                                "Dark crossing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));

        assertThat(auditEntries(incident)).isEmpty();
    }

    // T2
    @Test
    void aPriorityNeedsItsJustification() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        for (Map<String, Object> body :
                List.of(
                        Map.<String, Object>of("version", 0, "priority", "HIGH"),
                        Map.<String, Object>of(
                                "version", 0, "priority", "HIGH", "priorityReason", "   "),
                        // Blank for the \\S of the contract, although String.isBlank says no.
                        Map.<String, Object>of(
                                "version", 0, "priority", "HIGH", "priorityReason", "\u00A0"),
                        Map.<String, Object>of(
                                "version",
                                0,
                                "priority",
                                "HIGH",
                                "priorityReason",
                                "x".repeat(2001)))) {
            expectInvalid(classify(operator, incident, body), "priorityReason");
        }
        assertUnclassified(incident);
    }

    @Test
    void aJustificationIsCountedInCharactersAndNeedsAPriority() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        expectInvalid(
                classify(
                        operator,
                        incident,
                        Map.of("version", 0, "categoryId", lighting, "priorityReason", REASON)),
                "priorityReason");
        // 2000 emojis are 2000 characters but 4000 UTF-16 units.
        classify(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                0,
                                "priority",
                                "LOW",
                                "priorityReason",
                                "🚧".repeat(2000)))
                .andExpect(status().isOk());
    }

    // T5 and T11
    @Test
    void theCategoryMustExistAndBeActive() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");
        UUID retired = category("Retired " + UUID.randomUUID(), false);

        expectInvalid(
                classify(operator, incident, Map.of("version", 0, "categoryId", retired)),
                "categoryId");
        expectInvalid(
                classify(operator, incident, Map.of("version", 0, "categoryId", UUID.randomUUID())),
                "categoryId");
        assertUnclassified(incident);
    }

    @Test
    void everyInvalidFieldIsListedAtOnce() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        String body =
                classify(
                                operator,
                                incident,
                                Map.of(
                                        "version",
                                        0,
                                        "categoryId",
                                        UUID.randomUUID(),
                                        "priority",
                                        "HIGH"))
                        .andExpect(status().isUnprocessableContent())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.errors[*].field"))
                .containsExactly("categoryId", "priorityReason");
    }

    @Test
    void somethingHasToBeSent() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        expectInvalid(classify(operator, incident, Map.of("version", 0)), "categoryId");
    }

    // T11
    @Test
    void anUnknownPriorityIsAMalformedRequest() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        classify(
                        operator,
                        incident,
                        Map.of("version", 0, "priority", "URGENT", "priorityReason", REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertUnclassified(incident);
    }

    // T6 and T12. ADR-0020: 403 comes first, also when the body would be a 422.
    @Test
    void onlyOperatorsAndAdministratorsCanClassify() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");
        var valid = json(Map.of("version", 0, "categoryId", lighting));

        for (Role role : List.of(Role.TECHNICIAN, Role.CITIZEN, Role.ANALYST)) {
            for (String content : List.of(valid, "{}")) {
                mvc.perform(
                                patch(path(incident))
                                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(content))
                        .andExpect(status().isForbidden());
            }
        }
        mvc.perform(patch(path(incident)).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        assertUnclassified(incident);
    }

    // T12
    @Test
    void anIncidentThatDoesNotExistIsNotFound() throws Exception {
        classify(operator, UUID.randomUUID(), Map.of("version", 0, "categoryId", lighting))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        // The version is checked after the incident is found.
        classify(operator, UUID.randomUUID(), Map.of("categoryId", lighting))
                .andExpect(status().isNotFound());
    }

    @Test
    void theVersionIsRequired() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");

        expectInvalid(classify(operator, incident, Map.of("categoryId", lighting)), "version");
        expectInvalid(
                classify(operator, incident, Map.of("version", -1, "categoryId", lighting)),
                "version");
        assertUnclassified(incident);
    }

    // ADR-0020: invalid data, the version included, comes after an incident that cannot change.
    @Test
    void aMissingVersionIsCheckedAfterTheStatus() throws Exception {
        for (String status : List.of("REJECTED", "CLOSED")) {
            UUID incident = unclassifiedIncident(status);

            expectConflict(
                    classify(operator, incident, Map.of("categoryId", lighting)),
                    "CLASSIFICATION_NOT_ALLOWED");
            expectConflict(
                    classify(operator, incident, Map.of("version", -1, "categoryId", lighting)),
                    "CLASSIFICATION_NOT_ALLOWED");
            assertUnclassified(incident);
        }
    }

    // T8
    @Test
    void aStaleVersionIsAConcurrentModificationAndChangesNothing() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");
        jdbc.update("UPDATE incident SET version = 4 WHERE id = ?", incident);

        expectConflict(
                classify(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                3,
                                "categoryId",
                                lighting,
                                "priority",
                                "HIGH",
                                "priorityReason",
                                REASON)),
                "CONCURRENT_MODIFICATION");

        assertUnclassified(incident);
    }

    // T10. The version is checked before the status, as in ADR-0020.
    @Test
    void aRejectedOrClosedIncidentCannotBeClassified() throws Exception {
        for (String status : List.of("REJECTED", "CLOSED")) {
            UUID incident = unclassifiedIncident(status);

            expectConflict(
                    classify(operator, incident, Map.of("version", 0, "categoryId", lighting)),
                    "CLASSIFICATION_NOT_ALLOWED");
            expectConflict(
                    classify(operator, incident, Map.of("version", 1, "categoryId", lighting)),
                    "CONCURRENT_MODIFICATION");
            assertUnclassified(incident);
        }
    }

    @Test
    void everyOtherStatusCanBeClassified() throws Exception {
        for (String status :
                List.of(
                        "REPORTED",
                        "VALIDATED",
                        "ASSIGNED",
                        "IN_PROGRESS",
                        "RESOLVED",
                        "REOPENED")) {
            UUID incident = unclassifiedIncident(status);

            classify(operator, incident, Map.of("version", 0, "categoryId", roads))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(status));
        }
    }

    // #47: priorityReason is staff-only.
    @Test
    void staffSeeTheJustificationAndCitizensDoNot() throws Exception {
        UUID incident = classifiedIncident("VALIDATED");

        mvc.perform(
                        get(INCIDENTS + "/" + incident)
                                .header(HttpHeaders.AUTHORIZATION, bearer(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorityReason").value("Dark crossing"));
        mvc.perform(
                        get(INCIDENTS + "/" + incident)
                                .header(HttpHeaders.AUTHORIZATION, bearer(citizen)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorityReason").doesNotExist())
                .andExpect(jsonPath("$.priority").doesNotExist());
    }

    // T16: in order, with actor, instant and only the values that changed.
    @Test
    void theChangesAreListedOldestFirstWithTheNamesOfTheirCategories() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");
        classify(
                        operator,
                        incident,
                        Map.of(
                                "version",
                                0,
                                "categoryId",
                                lighting,
                                "priority",
                                "LOW",
                                "priorityReason",
                                REASON))
                .andExpect(status().isOk());
        classify(
                        administrator,
                        incident,
                        Map.of("version", versionOf(incident), "categoryId", roads))
                .andExpect(status().isOk());

        String body =
                changes(operator, incident, "")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.page.totalElements").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.content[*].id"))
                .containsExactlyElementsOf(
                        auditEntries(incident).stream().map(e -> e.get("id").toString()).toList());
        assertThat((String) JsonPath.read(body, "$.content[0].actor.id"))
                .isEqualTo(operator.toString());
        assertThat((String) JsonPath.read(body, "$.content[0].actor.name"))
                .isEqualTo("Olga Operator");
        assertThat((Object) JsonPath.read(body, "$.content[0].before.category")).isNull();
        assertThat((Object) JsonPath.read(body, "$.content[0].before.priority")).isNull();
        assertThat((String) JsonPath.read(body, "$.content[0].after.category.name"))
                .startsWith("Lighting");
        assertThat((String) JsonPath.read(body, "$.content[0].after.priority")).isEqualTo("LOW");
        assertThat((String) JsonPath.read(body, "$.content[0].after.priorityReason"))
                .isEqualTo(REASON);
        // Only the category changed the second time.
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.content[1].before"))
                .containsOnlyKeys("category");
        assertThat((String) JsonPath.read(body, "$.content[1].before.category.name"))
                .startsWith("Lighting");
        assertThat((String) JsonPath.read(body, "$.content[1].after.category.name"))
                .startsWith("Roads");
        assertThat((String) JsonPath.read(body, "$.content[1].actor.name"))
                .isEqualTo("Adrián Admin");
        assertThat((String) JsonPath.read(body, "$.content[0].instant"))
                .isLessThanOrEqualTo(JsonPath.read(body, "$.content[1].instant"));
    }

    // T17
    @Test
    void staffAndTheAssignedTechnicianReadTheChanges() throws Exception {
        UUID incident = unclassifiedIncident("ASSIGNED");
        UUID otherTechnician = user(Role.TECHNICIAN, "Teresa Technician");
        assign(incident, technician);

        for (UUID reader : List.of(operator, administrator, technician)) {
            changes(reader, incident, "").andExpect(status().isOk());
        }
        changes(otherTechnician, incident, "").andExpect(status().isNotFound());
        for (Role role : List.of(Role.CITIZEN, Role.ANALYST)) {
            mvc.perform(
                            get(changesPath(incident))
                                    .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(changesPath(incident))).andExpect(status().isUnauthorized());
        changes(operator, UUID.randomUUID(), "").andExpect(status().isNotFound());
    }

    // T18
    @Test
    void anIncidentNeverClassifiedHasAnEmptyPage() throws Exception {
        changes(operator, classifiedIncident("REPORTED"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // T19
    @Test
    void onlyTheClassificationEntriesOfThisIncidentAreListed() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");
        UUID other = unclassifiedIncident("REPORTED");
        classify(operator, incident, Map.of("version", 0, "categoryId", lighting))
                .andExpect(status().isOk());
        classify(operator, other, Map.of("version", 0, "categoryId", roads))
                .andExpect(status().isOk());
        // Another action about the same id, and an entry without a target.
        auditEntry("UPDATE_USER", "USER", incident);
        auditEntry("CREATE_USER", null, null);

        changes(operator, incident, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].after.category.id").value(lighting.toString()));
    }

    // T20
    @Test
    void theChangesArePagedWithoutRepeatingOrMissingAny() throws Exception {
        UUID incident = unclassifiedIncident("REPORTED");
        var priorities = List.of("LOW", "MEDIUM", "HIGH", "LOW", "MEDIUM");
        for (String priority : priorities) {
            classify(
                            operator,
                            incident,
                            Map.of(
                                    "version",
                                    versionOf(incident),
                                    "priority",
                                    priority,
                                    "priorityReason",
                                    REASON))
                    .andExpect(status().isOk());
        }

        var seen = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            String body =
                    changes(operator, incident, "?size=2&page=" + page)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.page.totalPages").value(3))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            seen.addAll(JsonPath.<List<String>>read(body, "$.content[*].id"));
        }
        assertThat(seen)
                .containsExactlyElementsOf(
                        auditEntries(incident).stream().map(e -> e.get("id").toString()).toList());

        String newest =
                changes(operator, incident, "?sort=instant,desc&size=1")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(JsonPath.<List<String>>read(newest, "$.content[*].id"))
                .containsExactly(seen.getLast());
        changes(operator, incident, "?size=101").andExpect(status().isBadRequest());
        changes(operator, incident, "?sort=priority,asc").andExpect(status().isBadRequest());
    }

    private ResultActions classify(UUID caller, UUID incident, Map<String, Object> body)
            throws Exception {
        return mvc.perform(
                patch(path(incident))
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)));
    }

    private ResultActions changes(UUID caller, UUID incident, String query) throws Exception {
        return mvc.perform(
                get(changesPath(incident) + query)
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller)));
    }

    private String bearer(UUID userId) {
        return tokens.bearer(userId, roleOf(userId));
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

    private void assertUnclassified(UUID incident) {
        var row =
                jdbc.queryForMap(
                        "SELECT category_id, priority, priority_reason FROM incident WHERE id = ?",
                        incident);
        assertThat(row.get("category_id")).isNull();
        assertThat(row.get("priority")).isNull();
        assertThat(row.get("priority_reason")).isNull();
        assertThat(auditEntries(incident)).isEmpty();
    }

    private static String path(UUID incident) {
        return INCIDENTS + "/" + incident;
    }

    private static String changesPath(UUID incident) {
        return path(incident) + "/classification-changes";
    }

    // The bodies only hold numbers, UUIDs, enums and plain text without quotes.
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

    private UUID category(String name, boolean active) {
        return jdbc.queryForObject(
                "INSERT INTO category (name, is_active) VALUES (?, ?) RETURNING id",
                UUID.class,
                name,
                active);
    }

    private UUID unclassifiedIncident(String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.', ?,"
                                + " ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        status,
                        Timestamp.from(MONDAY),
                        Timestamp.from(MONDAY));
        location(incident);
        return incident;
    }

    private UUID classifiedIncident(String status) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, category_id, title, description, status,"
                                + " priority, priority_reason, priority_source, priority_setter_id,"
                                + " reported_at, updated_at) VALUES (?, ?, 'Broken streetlight', 'The"
                                + " lamp is off.', ?, 'HIGH', 'Dark crossing', 'MANUAL', ?, ?, ?)"
                                + " RETURNING id",
                        UUID.class,
                        citizen,
                        lighting,
                        status,
                        operator,
                        Timestamp.from(MONDAY),
                        Timestamp.from(MONDAY));
        location(incident);
        return incident;
    }

    private void location(UUID incident) {
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
    }

    private void assign(UUID incident, UUID assignedTechnician) {
        UUID department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Street lighting " + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO assignment (incident_id, department_id, technician_id, assigned_at)"
                        + " VALUES (?, ?, ?, ?)",
                incident,
                department,
                assignedTechnician,
                Timestamp.from(MONDAY));
    }

    private void auditEntry(String action, String targetType, UUID targetId) {
        jdbc.update(
                "INSERT INTO audit_log (actor_id, instant, action, target_type, target_id, details)"
                        + " VALUES (?, now(), ?, ?, ?, '{\"before\": {}, \"after\": {}}'::jsonb)",
                administrator,
                action,
                targetType,
                targetId);
    }

    private int versionOf(UUID incident) {
        return jdbc.queryForObject(
                "SELECT version FROM incident WHERE id = ?", Integer.class, incident);
    }

    private long historyEntries(UUID incident) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM status_change WHERE incident_id = ?", Long.class, incident);
    }

    /** The CLASSIFY_INCIDENT entries of the incident, oldest first, with their two sides. */
    private List<Map<String, Object>> auditEntries(UUID incident) {
        return jdbc.queryForList(
                "SELECT id, actor_id, (details -> 'before')::text AS before, (details -> 'after')::text AS after"
                        + " FROM audit_log WHERE action = 'CLASSIFY_INCIDENT' AND target_id = ?"
                        + " ORDER BY instant, id",
                incident);
    }
}
