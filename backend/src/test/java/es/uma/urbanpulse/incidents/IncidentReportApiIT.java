package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
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
 * Acceptance tests of #66 for reporting an incident through the API (#43, {@code createIncident}).
 *
 * <p>They are written before the feature (ATDD, CONTRIBUTING.md) from the issue, the contract
 * ({@code docs/api/openapi.yml}), {@code docs/api.md} and ADR-0020, so they fail until the endpoint
 * exists. The users are inserted with SQL and the stored incident is read back with SQL, so the
 * tests do not depend on the read endpoints or on the implementation.
 *
 * <p>Each test runs in a transaction that is rolled back, so the tests never see each other's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class})
@Transactional
class IncidentReportApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";

    // Calle Larios, Málaga: latitude and longitude are far apart, so a swap shows.
    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;
    private static final double ACCURACY = 12.5;
    private static final double TOLERANCE = 1e-6;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;
    private UUID category;

    @BeforeEach
    void createCitizenAndCategory() {
        citizen = user(Role.CITIZEN);
        category =
                jdbc.queryForObject(
                        "INSERT INTO category (name) VALUES ('Lighting') RETURNING id", UUID.class);
    }

    // T4
    @Test
    void validReportCreatesAReportedIncidentAndPointsToIt() throws Exception {
        String body =
                report(citizen, Role.CITIZEN, validReport(category))
                        .andExpect(status().isCreated())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.status").value("REPORTED"))
                        .andExpect(jsonPath("$.title").value("Broken streetlight"))
                        .andExpect(jsonPath("$.category.id").value(category.toString()))
                        .andExpect(jsonPath("$.closedAt").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = JsonPath.read(body, "$.id");

        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT reporter_id, status, category_id FROM incident WHERE id = ?",
                        UUID.fromString(id));
        assertThat(stored.get("reporter_id")).isEqualTo(citizen);
        assertThat(stored.get("status")).isEqualTo("REPORTED");
        assertThat(stored.get("category_id")).isEqualTo(category);
    }

    // T4: the Location header points to the incident in the body.
    @Test
    void locationHeaderPointsToTheNewIncident() throws Exception {
        ResultActions result =
                report(citizen, Role.CITIZEN, validReport(category))
                        .andExpect(status().isCreated());
        String id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");

        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith(INCIDENTS + "/" + id);
    }

    // T4: the first history entry is created with the incident (ADR-0020).
    @Test
    void reportStartsTheHistoryWithOneEntryByTheCitizen() throws Exception {
        UUID id = created(report(citizen, Role.CITIZEN, validReport(category)));

        Map<String, Object> entry =
                jdbc.queryForMap(
                        "SELECT actor_id, previous_status, new_status, visible_to_citizen"
                                + " FROM status_change WHERE incident_id = ?",
                        id);
        assertThat(entry.get("actor_id")).isEqualTo(citizen);
        assertThat(entry.get("previous_status")).isNull();
        assertThat(entry.get("new_status")).isEqualTo("REPORTED");
        assertThat(entry.get("visible_to_citizen")).isEqualTo(true);
    }

    // T4: the position is stored and returned without swapping latitude and longitude, and the
    // place is "not available" because no external source is called while reporting (RF26).
    @Test
    void positionIsKeptAndThePlaceIsNotAvailableYet() throws Exception {
        String body =
                report(citizen, Role.CITIZEN, validReport(category))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.location.address").isEmpty())
                        .andExpect(jsonPath("$.location.neighborhood").isEmpty())
                        .andExpect(jsonPath("$.location.district").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(((Number) JsonPath.read(body, "$.location.latitude")).doubleValue())
                .isCloseTo(LATITUDE, offset(TOLERANCE));
        assertThat(((Number) JsonPath.read(body, "$.location.longitude")).doubleValue())
                .isCloseTo(LONGITUDE, offset(TOLERANCE));
        assertThat(((Number) JsonPath.read(body, "$.location.accuracy")).doubleValue())
                .isCloseTo(ACCURACY, offset(TOLERANCE));

        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        Map<String, Object> point =
                jdbc.queryForMap(
                        "SELECT ST_Y(coordinates::geometry) AS lat, ST_X(coordinates::geometry) AS lon,"
                                + " ST_SRID(coordinates::geometry) AS srid, accuracy"
                                + " FROM location WHERE incident_id = ?",
                        id);
        assertThat((Double) point.get("lat")).isCloseTo(LATITUDE, offset(TOLERANCE));
        assertThat((Double) point.get("lon")).isCloseTo(LONGITUDE, offset(TOLERANCE));
        assertThat(point.get("srid")).isEqualTo(4326);
        assertThat((Double) point.get("accuracy")).isCloseTo(ACCURACY, offset(TOLERANCE));
    }

    // T4: the category is optional; an operator classifies the incident later.
    @Test
    void reportWithoutCategoryIsAccepted() throws Exception {
        String withoutCategory =
                """
                {"title": "Broken streetlight", "description": "The lamp is off.",
                 "location": {"latitude": %s, "longitude": %s}}
                """
                        .formatted(LATITUDE, LONGITUDE);

        report(citizen, Role.CITIZEN, withoutCategory)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").isEmpty())
                .andExpect(jsonPath("$.location.accuracy").isEmpty());
    }

    // T5
    @Test
    void missingTitleAndLatitudeOutOfRangeAreReportedTogether() throws Exception {
        String invalid =
                """
                {"description": "The lamp is off.",
                 "location": {"latitude": 91, "longitude": %s}}
                """
                        .formatted(LONGITUDE);

        expectFieldErrors(report(citizen, Role.CITIZEN, invalid), "title", "location.latitude");
        assertThat(incidentsOf(citizen)).isZero();
    }

    // T5: every rule of CreateIncidentRequest and ReportedLocation answers 422 with its field.
    @Test
    void everyInvalidFieldIsListed() throws Exception {
        String invalid =
                """
                {"title": "   ", "description": "%s",
                 "location": {"latitude": %s, "longitude": 181, "accuracy": -1}}
                """
                        .formatted("x".repeat(2001), LATITUDE);

        expectFieldErrors(
                report(citizen, Role.CITIZEN, invalid),
                "title",
                "description",
                "location.longitude",
                "location.accuracy");
        assertThat(incidentsOf(citizen)).isZero();
    }

    // T5: a report without a location cannot be accepted (the position is required).
    @Test
    void missingLocationIsRejected() throws Exception {
        String invalid =
                """
                {"title": "Broken streetlight", "description": "The lamp is off."}
                """;

        expectFieldErrors(report(citizen, Role.CITIZEN, invalid), "location");
        assertThat(incidentsOf(citizen)).isZero();
    }

    // T5: an unknown or inactive category answers 422, as the contract says.
    @Test
    void unknownOrInactiveCategoryIsRejected() throws Exception {
        UUID inactive =
                jdbc.queryForObject(
                        "INSERT INTO category (name, is_active) VALUES ('Old', false) RETURNING id",
                        UUID.class);

        for (UUID categoryId : new UUID[] {UUID.randomUUID(), inactive}) {
            expectFieldErrors(report(citizen, Role.CITIZEN, validReport(categoryId)), "categoryId");
        }
        assertThat(incidentsOf(citizen)).isZero();
    }

    // T5: a body that is not JSON is a malformed request, not invalid data (docs/api.md).
    @Test
    void malformedJsonIsABadRequest() throws Exception {
        report(citizen, Role.CITIZEN, "{\"title\": \"Broken streetlight\",")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(incidentsOf(citizen)).isZero();
    }

    // T6: only citizens report incidents.
    @Test
    void staffRolesAreForbiddenAndNothingIsStored() throws Exception {
        for (Role role :
                new Role[] {
                    Role.MUNICIPAL_OPERATOR, Role.TECHNICIAN, Role.ADMINISTRATOR, Role.ANALYST
                }) {
            UUID staff = user(role);
            report(staff, role, validReport(category))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            assertThat(incidentsOf(staff)).as("incidents of %s", role).isZero();
        }
    }

    // T6: no token, no report.
    @Test
    void reportWithoutATokenIsUnauthorized() throws Exception {
        mvc.perform(
                        post(INCIDENTS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validReport(category)))
                .andExpect(status().isUnauthorized());
    }

    // T7: the reporter comes from the token; a reporter or a status in the body is ignored.
    @Test
    void reporterIsTheCallerAndTheBodyCannotChooseItOrTheStatus() throws Exception {
        UUID otherCitizen = user(Role.CITIZEN);
        String withExtras =
                """
                {"title": "Broken streetlight", "description": "The lamp is off.",
                 "reporterId": "%s", "status": "CLOSED",
                 "location": {"latitude": %s, "longitude": %s}}
                """
                        .formatted(otherCitizen, LATITUDE, LONGITUDE);

        UUID id = created(report(citizen, Role.CITIZEN, withExtras));

        Map<String, Object> stored =
                jdbc.queryForMap("SELECT reporter_id, status FROM incident WHERE id = ?", id);
        assertThat(stored.get("reporter_id")).isEqualTo(citizen);
        assertThat(stored.get("status")).isEqualTo("REPORTED");
        assertThat(incidentsOf(otherCitizen)).isZero();
    }

    private String validReport(UUID categoryId) {
        return """
                {"title": "Broken streetlight", "description": "The lamp is off since Monday.",
                 "categoryId": "%s",
                 "location": {"latitude": %s, "longitude": %s, "accuracy": %s}}
                """
                .formatted(categoryId, LATITUDE, LONGITUDE, ACCURACY);
    }

    private ResultActions report(UUID userId, Role role, String body) throws Exception {
        return mvc.perform(
                post(INCIDENTS)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId, role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static UUID created(ResultActions result) throws Exception {
        String body =
                result.andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static void expectFieldErrors(ResultActions result, String... fields) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String field : fields) {
            result.andExpect(jsonPath("$.errors[?(@.field == '%s')]".formatted(field)).exists());
        }
    }

    private int incidentsOf(UUID reporter) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM incident WHERE reporter_id = ?",
                        Integer.class,
                        reporter);
        return count == null ? 0 : count;
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
}
