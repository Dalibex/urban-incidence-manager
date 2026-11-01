package es.uma.urbanpulse.incidents.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.entity.Category;
import es.uma.urbanpulse.incidents.repository.CategoryRepository;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.UUID;
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
 * Acceptance tests of the report operation of the API (RF03, RF04, #37, contract method {@code
 * createIncident} in {@code docs/api/openapi.yml}).
 *
 * <p>Real tokens issued with the test secret go through the same validation as in production
 * ({@link TestTokens}); the reporter is the user of the token, never a property of the body. The
 * endpoint keeps an external source out of the report path (RF26): the address, the neighborhood
 * and the district stay {@code null} ("not available") and nothing else is written at creation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class})
class IncidentControllerIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private CategoryRepository categories;

    // T1: the incident starts in REPORTED with its first history entry, the same transaction
    // writes the report, its location and the status change, and the Location header offers it.
    @Test
    void citizenReportsAnIncidentWithItsLocationAndOneHistoryEntry() throws Exception {
        UUID citizen = insertUser(Role.CITIZEN);
        UUID categoryId = categories.save(category("Street lighting")).getId();
        String body =
                """
                {"title": "Street light off",
                 "description": "It has been off for three days.",
                 "categoryId": "%s",
                 "location": {"latitude": %s, "longitude": %s, "accuracy": 12.5}}
                """
                        .formatted(categoryId, LATITUDE, LONGITUDE);

        ResultActions result =
                mvc.perform(
                                json(post(INCIDENTS), body)
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(citizen, Role.CITIZEN)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isNotEmpty())
                        .andExpect(jsonPath("$.status").value("REPORTED"))
                        .andExpect(jsonPath("$.category.id").value(categoryId.toString()))
                        .andExpect(jsonPath("$.location.latitude").value(LATITUDE))
                        .andExpect(jsonPath("$.location.longitude").value(LONGITUDE))
                        .andExpect(jsonPath("$.location.accuracy").value(12.5));

        UUID id = UUID.fromString(read(result, "$.id"));
        result.andExpect(header().string(HttpHeaders.LOCATION, endsWith(INCIDENTS + "/" + id)));

        assertThat(stored("SELECT status FROM incident WHERE id = ?", id)).isEqualTo("REPORTED");
        assertThat(stored("SELECT reporter_id FROM incident WHERE id = ?", UUID.class, id))
                .isEqualTo(citizen);
        assertThat(
                        stored(
                                "SELECT ST_Y(coordinates::geometry) FROM location"
                                        + " WHERE incident_id = ?",
                                Double.class,
                                id))
                .isEqualTo(LATITUDE);
        assertThat(
                        stored(
                                "SELECT ST_X(coordinates::geometry) FROM location"
                                        + " WHERE incident_id = ?",
                                Double.class,
                                id))
                .isEqualTo(LONGITUDE);
        // RF26: no external source is consulted while reporting; "not available yet" is null.
        assertThat(stored("SELECT address FROM location WHERE incident_id = ?", id)).isNull();
        assertThat(stored("SELECT neighborhood FROM location WHERE incident_id = ?", id)).isNull();
        assertThat(stored("SELECT district FROM location WHERE incident_id = ?", id)).isNull();

        // Exactly one entry, the report itself: it has no previous status and the actor is the
        // reporter (ADR-0020, row "— → REPORTED").
        assertThat(count("SELECT count(*) FROM status_change WHERE incident_id = ?", id))
                .isEqualTo(1);
        assertThat(
                        stored(
                                "SELECT new_status FROM status_change WHERE incident_id = ?",
                                String.class,
                                id))
                .isEqualTo("REPORTED");
        assertThat(
                        stored(
                                "SELECT previous_status FROM status_change WHERE incident_id = ?",
                                String.class,
                                id))
                .isNull();
        assertThat(
                        stored(
                                "SELECT actor_id FROM status_change WHERE incident_id = ?",
                                UUID.class,
                                id))
                .isEqualTo(citizen);
    }

    // T2: every invalid field is reported at once (422 VALIDATION_FAILED), so the form can show
    // them all; nothing is stored.
    @Test
    void invalidCoordinatesAndBlankTitleAreAProblemWithErrors() throws Exception {
        UUID citizen = insertUser(Role.CITIZEN);
        String body =
                """
                {"title": "   ",
                 "description": "It has been off for three days.",
                 "location": {"latitude": 90.5, "longitude": -180.5, "accuracy": 12.5}}
                """;

        expectFieldError(
                performReport(citizen, body), "title", "location.latitude", "location.longitude");

        assertThat(count("SELECT count(*) FROM incident WHERE reporter_id = ?", citizen))
                .isEqualTo(0);
    }

    // T2: an unknown category is also a 422, built in the service, so the caller may repeat the
    // report with another category. Bean validation passes first: the body is well formed.
    @Test
    void anUnknownCategoryIsAValidationError() throws Exception {
        UUID citizen = insertUser(Role.CITIZEN);
        String body =
                """
                {"title": "Street light off",
                 "description": "It has been off for three days.",
                 "categoryId": "%s",
                 "location": {"latitude": %s, "longitude": %s, "accuracy": 12.5}}
                """
                        .formatted(UUID.randomUUID(), LATITUDE, LONGITUDE);

        expectFieldError(performReport(citizen, body), "categoryId");

        assertThat(count("SELECT count(*) FROM incident WHERE reporter_id = ?", citizen))
                .isEqualTo(0);
    }

    // T3: only CITIZEN reports. @Valid is evaluated before @PreAuthorize, so an invalid body
    // answers 422 with the field errors even for a technician; a valid body is needed to reach
    // the role check and get the 403.
    @Test
    void onlyACitizenCanReportAndUnauthenticatedRequestsAreRejected() throws Exception {
        UUID technician = insertUser(Role.TECHNICIAN);
        String body = validBodyWithoutCategory();

        mvc.perform(
                        json(post(INCIDENTS), body)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(technician, Role.TECHNICIAN)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.detail").value("You are not allowed to perform this action."));

        mvc.perform(json(post(INCIDENTS), body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void aTokenOfAnUnknownUserIsUnauthorized() throws Exception {
        String body = validBodyWithoutCategory();

        // The token is signed and says CITIZEN, but there is no such user: the reporter must
        // exist, so the report is refused (authorization on the action and the resource).
        mvc.perform(
                        json(post(INCIDENTS), body)
                                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(Role.CITIZEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(count("SELECT count(*) FROM incident")).isEqualTo(0);
    }

    private ResultActions performReport(UUID citizen, String body) throws Exception {
        return mvc.perform(
                json(post(INCIDENTS), body)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(citizen, Role.CITIZEN)));
    }

    private String validBodyWithoutCategory() {
        return """
                {"title": "Street light off",
                 "description": "It has been off for three days.",
                 "location": {"latitude": %s, "longitude": %s, "accuracy": 12.5}}
                """
                .formatted(LATITUDE, LONGITUDE);
    }

    private UUID insertUser(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                role.name() + " reporter",
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test",
                role.name());
    }

    private static Category category(String name) {
        Category category = new Category();
        category.setName(name);
        return category;
    }

    /** A {@code 422} problem with one entry of {@code errors} per invalid field. */
    private static void expectFieldError(ResultActions result, String... fields) throws Exception {
        result.andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String field : fields) {
            result.andExpect(jsonPath("$.errors[?(@.field == '%s')]".formatted(field)).exists());
        }
    }

    private static MockHttpServletRequestBuilder json(
            MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private <T> T stored(String sql, Class<T> type, UUID incidentId) {
        return jdbc.queryForObject(sql, type, incidentId);
    }

    private String stored(String sql, UUID incidentId) {
        return jdbc.queryForObject(sql, String.class, incidentId);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }
}
