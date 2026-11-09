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
import java.util.HashSet;
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
 * Acceptance tests of #73 (T2 to T4 and T6 to T9) for the internal comments of #50: {@code
 * listIncidentComments} and {@code createIncidentComment}.
 *
 * <p>They are written from the issues, the contract ({@code docs/api/openapi.yml}) and {@code
 * docs/security.md}, not from the implementation: the data is inserted with SQL on the Flyway
 * schema, every request goes through HTTP and its effect is checked in the database. Every response
 * is also validated against the contract ({@link ApiContract}). The rows of each test are deleted
 * after it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class IncidentCommentApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final int MAX_TEXT_LENGTH = 2000;

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-11-02T10:00:00Z");
    private static final String NOTE = "The neighbour at number 14 has the key of the cabinet.";

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
    private UUID department;

    @BeforeEach
    void createUsersAndReferenceData() {
        citizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
        administrator = user(Role.ADMINISTRATOR);
        technician = user(Role.TECHNICIAN);
        otherTechnician = user(Role.TECHNICIAN);
        analyst = user(Role.ANALYST);
        department =
                jdbc.queryForObject(
                        "INSERT INTO department (name) VALUES (?) RETURNING id",
                        UUID.class,
                        "Street lighting " + UUID.randomUUID());
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        for (UUID incident : createdIncidents) {
            jdbc.update("DELETE FROM comment WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM status_change WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM assignment WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
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

    // T2: an operator, an administrator and the assigned technician comment: 201, and the
    // comment is stored as internal, by the user of the token.
    @Test
    void theStaffOfTheIncidentComment() throws Exception {
        UUID incident = assignedTo(technician);

        for (UUID author : List.of(operator, administrator, technician)) {
            comment(author, incident, body(NOTE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.author.id").value(author.toString()))
                    .andExpect(jsonPath("$.text").value(NOTE))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT author_id, text, visible_to_citizen FROM comment"
                                + " WHERE incident_id = ? ORDER BY created_at, id",
                        incident);
        assertThat(rows)
                .extracting(row -> row.get("author_id"))
                .containsExactly(operator, administrator, technician);
        assertThat(rows)
                .allSatisfy(
                        row ->
                                assertThat(row)
                                        .containsEntry("text", NOTE)
                                        .containsEntry("visible_to_citizen", false));
    }

    // T2: the staff of the incident read its comments, oldest first, with their author.
    @Test
    void theStaffOfTheIncidentReadItsComments() throws Exception {
        UUID incident = assignedTo(technician);
        comment(operator, incident, body("First")).andExpect(status().isCreated());
        comment(technician, incident, body("Second")).andExpect(status().isCreated());

        for (UUID reader : List.of(operator, administrator, technician)) {
            ResultActions result =
                    list(reader, incident, "")
                            .andExpect(status().isOk())
                            .andExpect(
                                    jsonPath("$.content[0].author.id").value(operator.toString()))
                            .andExpect(
                                    jsonPath("$.content[1].author.id").value(technician.toString()))
                            .andExpect(jsonPath("$.page.totalElements").value(2));
            assertThat(texts(result)).containsExactly("First", "Second");
        }
    }

    // T2: a technician who is not the assigned one gets 404, as if the incident did not exist.
    @Test
    void anotherTechnicianGetsNotFound() throws Exception {
        UUID incident = assignedTo(technician);
        comment(operator, incident, body(NOTE)).andExpect(status().isCreated());

        expectNotFound(comment(otherTechnician, incident, body(NOTE)));
        expectNotFound(list(otherTechnician, incident, ""));

        assertThat(commentCount(incident)).isEqualTo(1);
    }

    // T3: the citizen who reported the incident gets no comment in any response.
    @Test
    void theCitizenNeverSeesAComment() throws Exception {
        UUID incident = assignedTo(technician);
        comment(operator, incident, body(NOTE)).andExpect(status().isCreated());

        for (String path :
                List.of(
                        INCIDENTS,
                        INCIDENTS + "/" + incident,
                        INCIDENTS + "/" + incident + "/status-changes",
                        INCIDENTS + "/" + incident + "/attachments")) {
            String response =
                    mvc.perform(as(citizen, get(path)))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(response).as(path).doesNotContain(NOTE);
        }
        expectForbidden(list(citizen, incident, ""));
    }

    // T4: a missing, empty or blank text, or one over the limit, is 422 and stores nothing.
    @Test
    void anInvalidTextIsUnprocessableAndStoresNothing() throws Exception {
        UUID incident = assignedTo(technician);

        for (String body :
                List.of(
                        "{}",
                        "{\"text\": null}",
                        body(""),
                        body(" \n "),
                        body("a".repeat(MAX_TEXT_LENGTH + 1)))) {
            expectUnprocessable(comment(operator, incident, body), "text");
        }
        assertThat(commentCount(incident)).isZero();

        comment(operator, incident, body("a".repeat(MAX_TEXT_LENGTH)))
                .andExpect(status().isCreated());
    }

    // CreateCommentRequest: an incident the caller may not see is a 404 before the text is checked,
    // so an invalid text does not reveal that the incident exists.
    @Test
    void anIncidentTheCallerMayNotSeeIsNotFoundBeforeTheTextIsChecked() throws Exception {
        UUID incident = assignedTo(technician);

        expectNotFound(comment(otherTechnician, incident, body("")));
        expectNotFound(comment(operator, UUID.randomUUID(), body("")));
    }

    // T6: an incident that does not exist is a 404; without a token it is a 401.
    @Test
    void unknownIncidentsAndRequestsWithoutAToken() throws Exception {
        UUID incident = assignedTo(technician);
        UUID unknown = UUID.randomUUID();

        expectNotFound(comment(operator, unknown, body(NOTE)));
        expectNotFound(list(operator, unknown, ""));
        mvc.perform(
                        post(commentsPath(incident))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(NOTE)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(commentsPath(incident))).andExpect(status().isUnauthorized());

        assertThat(commentCount(incident)).isZero();
    }

    // T7: citizens and analysts may neither read nor write comments (docs/security.md).
    @Test
    void citizensAndAnalystsAreForbidden() throws Exception {
        UUID incident = assignedTo(technician);

        for (UUID caller : List.of(citizen, analyst)) {
            expectForbidden(comment(caller, incident, body(NOTE)));
            expectForbidden(list(caller, incident, ""));
        }

        assertThat(commentCount(incident)).isZero();
    }

    // T8: once the assignment is deactivated, the former technician can no longer read or write.
    @Test
    void aFormerTechnicianGetsNotFound() throws Exception {
        UUID incident = assignedTo(technician);
        comment(technician, incident, body(NOTE)).andExpect(status().isCreated());
        jdbc.update("UPDATE assignment SET is_active = false WHERE incident_id = ?", incident);
        assign(incident, otherTechnician);

        expectNotFound(comment(technician, incident, body(NOTE)));
        expectNotFound(list(technician, incident, ""));

        assertThat(commentCount(incident)).isEqualTo(1);
        // The new technician reads what the former one wrote.
        list(otherTechnician, incident, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].author.id").value(technician.toString()));
    }

    // T9: across pages no comment repeats or goes missing, even with comments written at the same
    // instant, and the order is the one of the contract: createdAt, then id.
    @Test
    void pagesAreStableWithoutRepeatedOrMissingComments() throws Exception {
        UUID incident = assignedTo(technician);
        Instant sameInstant = Instant.parse("2026-11-03T08:00:00Z");
        for (int i = 0; i < 25; i++) {
            // Pairs of comments share their instant, so only the id can order them.
            insertComment(incident, operator, "Comment " + i, sameInstant.plusSeconds(i / 2));
        }
        List<String> expected =
                jdbc.queryForList(
                        "SELECT id::text FROM comment WHERE incident_id = ? ORDER BY created_at, id",
                        String.class,
                        incident);

        List<String> read = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String response =
                    list(operator, incident, "?size=10&page=" + page)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.page.totalElements").value(25))
                            .andExpect(jsonPath("$.page.totalPages").value(3))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            read.addAll(JsonPath.<List<String>>read(response, "$.content[*].id"));
        }

        assertThat(read).hasSize(25).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(read)).hasSize(25);
    }

    // T9: newest first is the documented alternative; other sorts and unknown parameters are 400.
    @Test
    void theListOnlyAcceptsTheDocumentedParameters() throws Exception {
        UUID incident = assignedTo(technician);
        insertComment(incident, operator, "Older", Instant.parse("2026-11-03T08:00:00Z"));
        insertComment(incident, technician, "Newer", Instant.parse("2026-11-03T09:00:00Z"));

        assertThat(
                        texts(
                                list(operator, incident, "?sort=createdAt,desc")
                                        .andExpect(status().isOk())))
                .containsExactly("Newer", "Older");
        for (String query : List.of("?sort=text,asc", "?author=" + operator, "?page=")) {
            list(operator, incident, query).andExpect(status().isBadRequest());
        }
    }

    /** An {@code ASSIGNED} incident of the citizen, actively assigned to {@code assignee}. */
    private UUID assignedTo(UUID assignee) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at) VALUES (?, 'Broken streetlight',"
                                + " 'The lamp is off.', 'ASSIGNED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(REPORTED_AT),
                        Timestamp.from(ASSIGNED_AT));
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
        assign(incident, assignee);
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

    private void insertComment(UUID incident, UUID author, String text, Instant createdAt) {
        jdbc.update(
                "INSERT INTO comment (incident_id, author_id, text, created_at, visible_to_citizen)"
                        + " VALUES (?, ?, ?, ?, false)",
                incident,
                author,
                text,
                Timestamp.from(createdAt));
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

    private int commentCount(UUID incident) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM comment WHERE incident_id = ?", Integer.class, incident);
    }

    private ResultActions comment(UUID caller, UUID incident, String body) throws Exception {
        return mvc.perform(
                as(
                        caller,
                        post(commentsPath(incident))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)));
    }

    private ResultActions list(UUID caller, UUID incident, String query) throws Exception {
        return mvc.perform(as(caller, get(commentsPath(incident) + query)));
    }

    /** The texts of the comments of one page, in the order of the response. */
    private static List<String> texts(ResultActions result) throws Exception {
        return JsonPath.read(
                result.andReturn().getResponse().getContentAsString(), "$.content[*].text");
    }

    private static String commentsPath(UUID incident) {
        return INCIDENTS + "/" + incident + "/comments";
    }

    private static String body(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"text\": \"" + escaped + "\"}";
    }

    private MockHttpServletRequestBuilder as(UUID userId, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId, roleOf(userId)));
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
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
