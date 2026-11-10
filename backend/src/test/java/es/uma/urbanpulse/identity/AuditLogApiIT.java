package es.uma.urbanpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Acceptance tests of #64 for {@code listAuditEntries} (#41): T2 to T4 and T6 to T11. T5 is the
 * contract, checked on every response by {@link ApiContract}; T1 (the screen) is manual, and the
 * screen side of T9 and T10 is in {@code AuditLogPage.test.tsx}.
 *
 * <p>They are written from the issue, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}, not from the implementation. Users and entries are inserted with SQL, except
 * in T11, where the entry is written by a real classification. Every check of behaviour goes
 * through HTTP.
 *
 * <p>Other integration tests commit entries that can never be deleted, so a test never lists the
 * whole trail: it filters by actors of its own, created for each test. Each test runs in a
 * transaction that is rolled back, which the append-only trigger allows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class AuditLogApiIT {

    private static final String AUDIT_ENTRIES = "/api/v1/audit-entries";
    private static final Instant MONDAY = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-11-03T09:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-11-04T09:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2026-11-05T09:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID administrator;
    private UUID otherAdministrator;
    private UUID technician;

    @BeforeEach
    void createUsers() {
        administrator = user("Isabel Admin", Role.ADMINISTRATOR);
        otherAdministrator = user("Pedro Admin", Role.ADMINISTRATOR);
        technician = user("Sergio Technician", Role.TECHNICIAN);
    }

    // T2: only administrators read the trail.
    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"CITIZEN", "MUNICIPAL_OPERATOR", "TECHNICIAN", "ANALYST"})
    void everyOtherRoleIsForbidden(Role role) throws Exception {
        entry(administrator, "CHANGE_ROLE", MONDAY, technician);

        mvc.perform(
                        get(AUDIT_ENTRIES)
                                .param("actorId", administrator.toString())
                                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    // T2: the administrator reads the entry: who, what, when and on which record.
    @Test
    void administratorReadsWhoDidWhatWhenAndOnWhichRecord() throws Exception {
        var id = entry(administrator, "CHANGE_ROLE", MONDAY, technician);

        list("actorId", administrator.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].instant").value("2026-11-02T09:00:00Z"))
                .andExpect(jsonPath("$.content[0].actor.id").value(administrator.toString()))
                .andExpect(jsonPath("$.content[0].actor.name").value("Isabel Admin"))
                .andExpect(jsonPath("$.content[0].action").value("CHANGE_ROLE"))
                .andExpect(jsonPath("$.content[0].target.type").value("USER"))
                .andExpect(jsonPath("$.content[0].target.id").value(technician.toString()))
                .andExpect(jsonPath("$.content[0].target.label").value("Sergio Technician"))
                .andExpect(jsonPath("$.content[0].details.before.role").value("TECHNICIAN"))
                .andExpect(jsonPath("$.content[0].details.after.role").value("MUNICIPAL_OPERATOR"));
    }

    // T2: the names are the current ones, and a record that no longer exists has no label.
    @Test
    void namesAreTheCurrentOnesAndAMissingRecordHasNoLabel() throws Exception {
        entry(administrator, "CHANGE_ROLE", MONDAY, technician);
        var gone = UUID.randomUUID();
        entry(administrator, "DISABLE_USER", TUESDAY, gone);
        jdbc.update("UPDATE users SET name = 'Isabel Romero' WHERE id = ?", administrator);
        jdbc.update("UPDATE users SET name = 'Sergio Molina' WHERE id = ?", technician);

        list("actorId", administrator.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].target.id").value(gone.toString()))
                .andExpect(jsonPath("$.content[0].target.label").doesNotExist())
                .andExpect(jsonPath("$.content[1].actor.name").value("Isabel Romero"))
                .andExpect(jsonPath("$.content[1].target.label").value("Sergio Molina"));
    }

    // T3: actor, actions and range together; `from` is included and `to` is not.
    @Test
    void filtersByActorActionsAndRangeTogether() throws Exception {
        entry(administrator, "CREATE_USER", MONDAY, technician);
        var wanted = entry(administrator, "CHANGE_ROLE", TUESDAY, technician);
        entry(administrator, "DISABLE_USER", WEDNESDAY, technician);
        entry(administrator, "UPDATE_USER", TUESDAY, technician);
        entry(otherAdministrator, "CHANGE_ROLE", TUESDAY, technician);

        list(
                        "actorId", administrator.toString(),
                        "action", "CHANGE_ROLE",
                        "action", "DISABLE_USER",
                        "from", "2026-11-03T09:00:00Z",
                        "to", "2026-11-04T09:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(wanted.toString()));
    }

    // T3: consecutive ranges never count an entry twice, and together they hold every entry.
    @Test
    void consecutiveRangesCountEachEntryOnce() throws Exception {
        entry(administrator, "CREATE_USER", MONDAY, technician);
        entry(administrator, "CHANGE_ROLE", TUESDAY, technician);
        entry(administrator, "DISABLE_USER", WEDNESDAY, technician);

        var first = ids("from", "2026-11-02T00:00:00Z", "to", "2026-11-03T09:00:00Z");
        var second = ids("from", "2026-11-03T09:00:00Z", "to", "2026-11-05T00:00:00Z");

        assertThat(first).hasSize(1).doesNotContainAnyElementsOf(second);
        assertThat(second).hasSize(2);
    }

    // T3: newest first by default, and the opposite with sort.
    @Test
    void newestFirstByDefaultAndOldestFirstOnRequest() throws Exception {
        var monday = entry(administrator, "CREATE_USER", MONDAY, technician);
        var wednesday = entry(administrator, "DISABLE_USER", WEDNESDAY, technician);
        var tuesday = entry(administrator, "CHANGE_ROLE", TUESDAY, technician);

        assertThat(ids()).containsExactly(wednesday, tuesday, monday);
        assertThat(ids("sort", "instant,asc")).containsExactly(monday, tuesday, wednesday);
    }

    // T3: entries of the same instant keep one order across pages: every entry once, none lost.
    @Test
    void pagesOfEntriesWithTheSameInstantHoldEachEntryOnce() throws Exception {
        var all = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            all.add(entry(administrator, "UPDATE_USER", THURSDAY, technician));
        }

        var pages = new ArrayList<UUID>();
        for (int page = 0; page < 3; page++) {
            var body =
                    list("actorId", administrator.toString(), "size", "2", "page", "" + page)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.page.totalElements").value(5))
                            .andExpect(jsonPath("$.page.totalPages").value(3))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            pages.addAll(idsIn(body));
        }

        assertThat(pages).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        // The same request twice gives the same order.
        assertThat(ids("size", "5")).containsExactlyElementsOf(pages);
    }

    // T4: the trail has no operation to create, change or delete an entry.
    @Test
    void noRequestCreatesChangesOrDeletesAnEntry() throws Exception {
        var id = entry(administrator, "CHANGE_ROLE", MONDAY, technician);
        var admin = tokens.bearer(administrator, Role.ADMINISTRATOR);
        var body = "{\"action\":\"CREATE_USER\"}";

        List<MockHttpServletRequestBuilder> requests =
                List.of(
                        post(AUDIT_ENTRIES).contentType(MediaType.APPLICATION_JSON).content(body),
                        put(AUDIT_ENTRIES + "/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        patch(AUDIT_ENTRIES + "/" + id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        delete(AUDIT_ENTRIES + "/" + id),
                        delete(AUDIT_ENTRIES));
        for (var request : requests) {
            var answer =
                    mvc.perform(request.header(HttpHeaders.AUTHORIZATION, admin))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            assertThat(answer).as(request.toString()).isIn(404, 405);
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT action FROM audit_log WHERE id = ?", String.class, id))
                .isEqualTo("CHANGE_ROLE");
        assertThat(ids()).containsExactly(id);
    }

    // T6: `from` must be earlier than `to`.
    @ParameterizedTest
    @ValueSource(strings = {"2026-11-03T09:00:00Z", "2026-11-04T00:00:00Z"})
    void fromNotEarlierThanToIsABadRequest(String from) throws Exception {
        expectBadRequest(list("from", from, "to", "2026-11-03T09:00:00Z"));
    }

    // T6: a date-time without an offset is not an instant.
    @Test
    void aDateTimeWithoutOffsetIsABadRequest() throws Exception {
        expectBadRequest(list("from", "2026-11-01T00:00:00"));
        expectBadRequest(list("to", "2026-11-01"));
    }

    // T7: an unknown action, an actor that is not a UUID, a page size out of range, an unknown or
    // empty parameter, an unknown sort property.
    @Test
    void invalidValuesAreABadRequest() throws Exception {
        expectBadRequest(list("action", "DROP_TABLE"));
        expectBadRequest(list("action", "change_role"));
        expectBadRequest(list("action", "CREATE_USER,"));
        expectBadRequest(list("action", ""));
        expectBadRequest(list("actorId", "not-a-uuid"));
        expectBadRequest(list("size", "101"));
        expectBadRequest(list("size", "0"));
        expectBadRequest(list("page", "-1"));
        expectBadRequest(list("sort", "action"));
        expectBadRequest(list("actor", administrator.toString()));
    }

    // T7: the largest page size is allowed.
    @Test
    void aPageOfOneHundredIsAllowed() throws Exception {
        list("actorId", administrator.toString(), "size", "100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    // T8
    @Test
    void withoutAValidTokenItIsUnauthorized() throws Exception {
        mvc.perform(get(AUDIT_ENTRIES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.content").doesNotExist());
        mvc.perform(get(AUDIT_ENTRIES).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    // T9: filters that match nothing, an actor who never acted included, are an empty page.
    @Test
    void filtersThatMatchNothingAreAnEmptyPage() throws Exception {
        entry(administrator, "CHANGE_ROLE", MONDAY, technician);

        for (var query :
                List.of(
                        List.of("actorId", administrator.toString(), "action", "ENABLE_USER"),
                        List.of(
                                "actorId",
                                administrator.toString(),
                                "from",
                                "2026-11-03T00:00:00Z"),
                        List.of("actorId", UUID.randomUUID().toString()),
                        List.of("actorId", otherAdministrator.toString()))) {
            list(query.toArray(String[]::new))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }
    }

    // T11: the entry of a real change holds the values it changed, never personal data.
    @Test
    void anEntryOfARealChangeHoldsNoPersonalData() throws Exception {
        var operator = user("Raúl Operator", Role.MUNICIPAL_OPERATOR);
        var citizen = user("Ana Citizen", Role.CITIZEN);
        var category =
                jdbc.queryForObject(
                        "INSERT INTO category (name, is_active) VALUES (?, true) RETURNING id",
                        UUID.class,
                        "Lighting " + UUID.randomUUID());
        var incident = incident(citizen);

        mvc.perform(
                        patch("/api/v1/incidents/" + incident)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(operator, Role.MUNICIPAL_OPERATOR))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"version": 0, "categoryId": "%s", "priority": "HIGH",
                                         "priorityReason": "Dark crossing"}
                                        """
                                                .formatted(category)))
                .andExpect(status().isOk());

        var body =
                list("actorId", operator.toString())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.page.totalElements").value(1))
                        .andExpect(jsonPath("$.content[0].action").value("CLASSIFY_INCIDENT"))
                        .andExpect(jsonPath("$.content[0].actor.name").value("Raúl Operator"))
                        .andExpect(jsonPath("$.content[0].target.type").value("INCIDENT"))
                        .andExpect(jsonPath("$.content[0].target.id").value(incident.toString()))
                        .andExpect(jsonPath("$.content[0].target.label").value("Broken bench"))
                        .andExpect(jsonPath("$.content[0].details.before.categoryId").isEmpty())
                        .andExpect(
                                jsonPath("$.content[0].details.after.categoryId")
                                        .value(category.toString()))
                        .andExpect(jsonPath("$.content[0].details.after.priority").value("HIGH"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain(emailOf(operator), emailOf(citizen), passwordHashOf(operator))
                .doesNotContainIgnoringCase("email")
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("hash")
                .doesNotContainIgnoringCase("token");
    }

    // T11: no response holds the email or the hash of the actor or of the target.
    @Test
    void theListHoldsNoEmailHashOrToken() throws Exception {
        entry(administrator, "CHANGE_ROLE", MONDAY, technician);

        var body =
                list("actorId", administrator.toString())
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain(emailOf(administrator), emailOf(technician))
                .doesNotContain(passwordHashOf(administrator), passwordHashOf(technician))
                .doesNotContainIgnoringCase("email")
                .doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("token");
    }

    /** The listing as the administrator, with the query parameters given as name, value pairs. */
    private ResultActions list(String... nameValuePairs) throws Exception {
        var request =
                get(AUDIT_ENTRIES)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                tokens.bearer(administrator, Role.ADMINISTRATOR));
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            request.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return mvc.perform(request);
    }

    /** The ids of the entries of {@link #administrator} that match, in the order of the page. */
    private List<UUID> ids(String... nameValuePairs) throws Exception {
        var query = new ArrayList<>(List.of("actorId", administrator.toString()));
        query.addAll(List.of(nameValuePairs));
        var body =
                list(query.toArray(String[]::new))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return idsIn(body);
    }

    private static List<UUID> idsIn(String body) {
        List<String> ids = JsonPath.read(body, "$.content[*].id");
        return ids.stream().map(UUID::fromString).toList();
    }

    private static void expectBadRequest(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    /** An entry about a user, as the services write it: the role before and after. */
    private UUID entry(UUID actor, String action, Instant instant, UUID targetUser) {
        return jdbc.queryForObject(
                "INSERT INTO audit_log (actor_id, instant, action, target_id, target_type, details)"
                        + " VALUES (?, ?, ?, ?, 'USER',"
                        + " '{\"before\": {\"role\": \"TECHNICIAN\"},"
                        + " \"after\": {\"role\": \"MUNICIPAL_OPERATOR\"}}'::jsonb)"
                        + " RETURNING id",
                UUID.class,
                actor,
                Timestamp.from(instant),
                action,
                targetUser);
    }

    private UUID user(String name, Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role) VALUES (?, ?, ?, ?)"
                        + " RETURNING id",
                UUID.class,
                name,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}hash-" + UUID.randomUUID(),
                role.name());
    }

    private String emailOf(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }

    private String passwordHashOf(UUID user) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, user);
    }

    private UUID incident(UUID reporter) {
        var incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at)"
                                + " VALUES (?, 'Broken bench', 'Two slats are missing.', 'REPORTED',"
                                + " ?, ?) RETURNING id",
                        UUID.class,
                        reporter,
                        Timestamp.from(MONDAY),
                        Timestamp.from(MONDAY));
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.42, 36.7196), 4326)::geography)",
                incident);
        return incident;
    }
}
