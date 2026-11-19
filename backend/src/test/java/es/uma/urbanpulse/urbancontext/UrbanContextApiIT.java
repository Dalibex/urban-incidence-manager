package es.uma.urbanpulse.urbancontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Acceptance tests of #309 for #306 (T5 and T16 to T18): {@code getIncidentUrbanContext}.
 *
 * <p>They are written from the issues, the contract ({@code docs/api/openapi.yml}) and the
 * permission matrix of {@code docs/security.md}, not from the implementation: the incident and its
 * context are inserted with SQL on the Flyway schema, as the enrichment of #307 writes them, and
 * every request goes through HTTP. Every response is also validated against the contract ({@link
 * ApiContract}). The rows of each test are deleted after it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
class UrbanContextApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";

    private static final Instant REPORTED_AT = Instant.parse("2026-11-02T09:58:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-02T09:58:47Z");

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
            jdbc.update("DELETE FROM urban_context WHERE incident_id = ?", incident);
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

    // T5: an operator, an administrator and the assigned technician read a complete context: its
    // status, when it was stored, the weather and its source with the Open-Meteo attribution.
    @Test
    void theStaffOfTheIncidentReadItsContext() throws Exception {
        UUID incident = assignedTo(technician);
        context(incident, "COMPLETE", "RAINY", CAPTURED_AT);

        for (UUID reader : List.of(operator, administrator, technician)) {
            read(reader, incident)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETE"))
                    .andExpect(jsonPath("$.capturedAt").value("2026-11-02T09:58:47Z"))
                    .andExpect(jsonPath("$.weather.condition").value("RAINY"))
                    .andExpect(jsonPath("$.weather.source.name").value("Open-Meteo"))
                    .andExpect(
                            jsonPath("$.weather.source.attribution")
                                    .value("Weather data by Open-Meteo.com"))
                    .andExpect(jsonPath("$.weather.source.url").value("https://open-meteo.com/"));
        }
    }

    // T5, #306: a partial context is shown as it is, with the parts that are there.
    @Test
    void aPartialContextShowsWhatIsThere() throws Exception {
        UUID incident = assignedTo(technician);
        context(incident, "PARTIAL", "CLOUDY", CAPTURED_AT);

        read(operator, incident)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.capturedAt").value("2026-11-02T09:58:47Z"))
                .andExpect(jsonPath("$.weather.condition").value("CLOUDY"));
    }

    // T19 from the API side, #306: a pending, failed or unavailable context sends no weather and no
    // time, even when the row still holds an old value, so it is never shown as current.
    @Test
    void aContextWithoutValueSendsNullsNotAnOldValue() throws Exception {
        for (String status : List.of("PENDING", "ERROR", "UNAVAILABLE")) {
            UUID incident = assignedTo(technician);
            context(incident, status, "SUNNY", CAPTURED_AT);

            read(operator, incident)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(status))
                    .andExpect(jsonPath("$.capturedAt").value(nullValue()))
                    .andExpect(jsonPath("$.weather").value(nullValue()));
        }
    }

    // T16: without a token the answer is 401.
    @Test
    void withoutATokenItIsUnauthorized() throws Exception {
        UUID incident = assignedTo(technician);
        context(incident, "COMPLETE", "RAINY", CAPTURED_AT);

        mvc.perform(get(contextPath(incident))).andExpect(status().isUnauthorized());
    }

    // T17: the citizen, also for their own incident, and the analyst get 403, and nothing about the
    // context is sent.
    @Test
    void citizensAndAnalystsAreForbidden() throws Exception {
        UUID incident = assignedTo(technician);
        context(incident, "COMPLETE", "RAINY", CAPTURED_AT);

        for (UUID caller : List.of(citizen, analyst)) {
            expectNothingAbout(read(caller, incident).andExpect(status().isForbidden()));
        }
    }

    // T17: a technician who is not the assigned one gets 404, as getIncident does, so the answer
    // does not reveal that the incident exists.
    @Test
    void anotherTechnicianGetsNotFound() throws Exception {
        UUID incident = assignedTo(technician);
        context(incident, "COMPLETE", "RAINY", CAPTURED_AT);

        expectNothingAbout(read(otherTechnician, incident).andExpect(status().isNotFound()));
    }

    // T17: once the assignment is deactivated, the former technician gets 404 too.
    @Test
    void aFormerTechnicianGetsNotFound() throws Exception {
        UUID incident = assignedTo(technician);
        context(incident, "COMPLETE", "RAINY", CAPTURED_AT);
        jdbc.update("UPDATE assignment SET is_active = false WHERE incident_id = ?", incident);
        assign(incident, otherTechnician);

        expectNothingAbout(read(technician, incident).andExpect(status().isNotFound()));
        read(otherTechnician, incident).andExpect(status().isOk());
    }

    // T18: an incident that does not exist is a 404, and an id that is not a UUID a 400.
    @Test
    void anUnknownIncidentIsNotFound() throws Exception {
        expectProblem(read(operator, UUID.randomUUID()).andExpect(status().isNotFound()));
        expectProblem(
                mvc.perform(
                                get(INCIDENTS + "/not-a-uuid/urban-context")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(operator, Role.MUNICIPAL_OPERATOR)))
                        .andExpect(status().isBadRequest()));
    }

    // T18: an incident reported before the enrichment existed has no context row: 200 with
    // UNAVAILABLE and both parts null, never PENDING, 404 or 500.
    @Test
    void anIncidentWithoutAContextRowIsUnavailable() throws Exception {
        UUID incident = assignedTo(technician);

        for (UUID reader : List.of(operator, administrator, technician)) {
            read(reader, incident)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.capturedAt").value(nullValue()))
                    .andExpect(jsonPath("$.weather").value(nullValue()));
        }
        // Reading does not create the row: the report creates it (#307).
        assertThat(contextRows(incident)).isZero();
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
                        Timestamp.from(REPORTED_AT));
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                incident);
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
                Timestamp.from(REPORTED_AT));
    }

    private void context(UUID incident, String status, String weather, Instant capturedAt) {
        jdbc.update(
                "INSERT INTO urban_context (incident_id, status, weather, captured_at)"
                        + " VALUES (?, ?, ?, ?)",
                incident,
                status,
                weather,
                Timestamp.from(capturedAt));
    }

    private int contextRows(UUID incident) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM urban_context WHERE incident_id = ?",
                Integer.class,
                incident);
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

    private ResultActions read(UUID caller, UUID incident) throws Exception {
        return mvc.perform(
                get(contextPath(incident))
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, roleOf(caller))));
    }

    private Role roleOf(UUID userId) {
        return Role.valueOf(
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private static String contextPath(UUID incident) {
        return INCIDENTS + "/" + incident + "/urban-context";
    }

    /** A Problem body that says nothing about the weather of the incident. */
    private static void expectNothingAbout(ResultActions result) throws Exception {
        String body = expectProblem(result);
        assertThat(body).doesNotContain("RAINY", "Open-Meteo", "capturedAt");
    }

    private static String expectProblem(ResultActions result) throws Exception {
        return result.andExpect(
                        content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
