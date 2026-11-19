package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * A retry of a report with the same {@code Idempotency-Key} creates the incident only once (#289,
 * the cases of #302). Two requests at the same time, and a request that fails with {@code 500}, are
 * in {@code IncidentIdempotencyTransactionIT}, because both need committed transactions.
 *
 * <p>Each test runs in a transaction that is rolled back, and every response is checked against the
 * contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
class IncidentIdempotencyApiIT {

    private static final String INCIDENTS = "/api/v1/incidents";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @PersistenceContext private EntityManager entityManager;

    private UUID citizen;

    @BeforeEach
    void createCitizen() {
        citizen = citizen();
    }

    // T1
    @Test
    void aFirstReportWithAKeyCreatesTheIncident() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult result = created(report(citizen, key, report("Broken streetlight")));

        assertThat(incidentsOf(citizen)).isEqualTo(1);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith(INCIDENTS + "/" + id(result));
    }

    // T2: the answer is the same; nothing is created and the history keeps its one entry.
    @Test
    void aRetryWithTheSameKeyReturnsTheSameIncident() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = created(report(citizen, key, report("Broken streetlight")));

        MvcResult retry = created(report(citizen, key, report("Broken streetlight")));

        assertThat(id(retry)).isEqualTo(id(first));
        assertThat(retry.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(first.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(incidentsOf(citizen)).isEqualTo(1);
        assertThat(historyOf(id(first))).isEqualTo(1);
    }

    // T2: the key is a UUID, not a text: written in capitals it is the same key.
    @Test
    void theKeyIsTheSameWhateverTheCaseOfItsLetters() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = created(report(citizen, key, report("Broken streetlight")));

        MvcResult retry = created(report(citizen, key.toUpperCase(), report("Broken streetlight")));

        assertThat(id(retry)).isEqualTo(id(first));
    }

    // T2: the JSON may be written in another way; the data decides, not its formatting.
    @Test
    void aRetryWhoseJsonIsFormattedDifferentlyIsStillTheSameReport() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = created(report(citizen, key, report("Broken streetlight")));
        String reordered =
                """
                {"location": {"longitude": -4.4214, "latitude": 36.72130},
                   "description": "The lamp is off since Monday.", "title": "Broken streetlight"}
                """;

        MvcResult retry = created(report(citizen, key, reordered));

        assertThat(id(retry)).isEqualTo(id(first));
    }

    // T2: the answer shows the incident as it is now, not as it was when it was created.
    @Test
    void aRetryAnswersWithTheIncidentAsItIsNow() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID id = id(created(report(citizen, key, report("Broken streetlight"))));
        changeInTheDatabase("UPDATE incident SET status = 'VALIDATED' WHERE id = ?", id);

        report(citizen, key, report("Broken streetlight"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    // T3: the key decides, not the content.
    @Test
    void twoReportsWithTheSameBodyAndDifferentKeysCreateTwoIncidents() throws Exception {
        MvcResult first =
                created(
                        report(
                                citizen,
                                UUID.randomUUID().toString(),
                                report("Broken streetlight")));
        MvcResult second =
                created(
                        report(
                                citizen,
                                UUID.randomUUID().toString(),
                                report("Broken streetlight")));

        assertThat(id(second)).isNotEqualTo(id(first));
        assertThat(incidentsOf(citizen)).isEqualTo(2);
    }

    // T7: a request that failed stored nothing, so its key is still free for the retry.
    @Test
    void aRetryAfterARefusedRequestCreatesTheIncident() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID category =
                jdbc.queryForObject(
                        "INSERT INTO category (name, is_active) VALUES ('Lighting', false)"
                                + " RETURNING id",
                        UUID.class);
        String withCategory =
                """
                {"title": "Broken streetlight", "description": "The lamp is off since Monday.",
                 "categoryId": "%s", "location": {"latitude": 36.7213, "longitude": -4.4214}}
                """
                        .formatted(category);
        report(citizen, key, withCategory)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        changeInTheDatabase("UPDATE category SET is_active = true WHERE id = ?", category);

        created(report(citizen, key, withCategory));

        assertThat(incidentsOf(citizen)).isEqualTo(1);
    }

    // T9: another body with a key already used is refused, and the stored incident is kept.
    @Test
    void theSameKeyWithAnotherReportIsRefusedAndNothingChanges() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID id = id(created(report(citizen, key, report("Broken streetlight"))));

        for (String other :
                new String[] {
                    report("Fallen tree"),
                    """
                    {"title": "Broken streetlight", "description": "The lamp is off since Monday.",
                     "location": {"latitude": 36.7214, "longitude": -4.4214}}
                    """,
                    """
                    {"title": "Broken streetlight", "description": "The lamp is off since Monday.",
                     "location": {"latitude": 36.7213, "longitude": -4.4214, "accuracy": 5}}
                    """
                }) {
            report(citizen, key, other)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }

        assertThat(incidentsOf(citizen)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM incident WHERE id = ?", String.class, id))
                .isEqualTo("Broken streetlight");
        assertThat(historyOf(id)).isEqualTo(1);
    }

    // T10: the key belongs to the citizen who sent it; another citizen never gets that incident.
    @Test
    void anotherCitizenWithTheSameKeyGetsTheirOwnIncident() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID other = citizen();
        UUID first = id(created(report(citizen, key, report("Broken streetlight"))));

        UUID theirs = id(created(report(other, key, report("Broken streetlight"))));

        assertThat(theirs).isNotEqualTo(first);
        assertThat(incidentsOf(citizen)).isEqualTo(1);
        assertThat(incidentsOf(other)).isEqualTo(1);
    }

    // T11: only a UUID in its canonical form is a key; anything else is a malformed request.
    @Test
    void aKeyThatIsNotAUuidIsABadRequestAndNothingIsStored() throws Exception {
        for (String malformed :
                new String[] {
                    "not-a-uuid",
                    "",
                    "1-2-3-4-5",
                    UUID.randomUUID().toString().replace("-", ""),
                    UUID.randomUUID() + "0",
                    "x".repeat(200)
                }) {
            report(citizen, malformed, report("Broken streetlight"))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        assertThat(incidentsOf(citizen)).isZero();
    }

    // T12: without the header every request creates an incident, as older clients expect.
    @Test
    void withoutAKeyEveryRequestCreatesAnIncident() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(
                            post(INCIDENTS)
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            tokens.bearer(citizen, Role.CITIZEN))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(report("Broken streetlight")))
                    .andExpect(status().isCreated());
        }

        assertThat(incidentsOf(citizen)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM incident"
                                        + " WHERE reporter_id = ? AND idempotency_key IS NULL"
                                        + " AND idempotency_request_hash IS NULL",
                                Integer.class,
                                citizen))
                .isEqualTo(2);
    }

    // T13: the key is kept for as long as the incident, so an old one still finds it.
    @Test
    void aKeyStillFindsItsIncidentLongAfterTheReport() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID id = id(created(report(citizen, key, report("Broken streetlight"))));
        jdbc.update(
                "UPDATE incident SET reported_at = reported_at - interval '3 years',"
                        + " updated_at = updated_at - interval '3 years' WHERE id = ?",
                id);

        UUID retry = id(created(report(citizen, key, report("Broken streetlight"))));

        assertThat(retry).isEqualTo(id);
        assertThat(incidentsOf(citizen)).isEqualTo(1);
    }

    // T15: without a token nothing is read or stored, the key neither.
    @Test
    void aKeyWithoutATokenIsUnauthorizedAndNothingIsStored() throws Exception {
        String key = UUID.randomUUID().toString();

        mvc.perform(
                        post(INCIDENTS)
                                .header(IDEMPOTENCY_KEY, key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(report("Broken streetlight")))
                .andExpect(status().isUnauthorized());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM incident WHERE idempotency_key = ?",
                                Integer.class,
                                UUID.fromString(key)))
                .isZero();
    }

    /**
     * A change another request would have made. The test's requests share its transaction, so the
     * entities they loaded are forgotten, as a new request would not have them.
     */
    private void changeInTheDatabase(String sql, Object... arguments) {
        jdbc.update(sql, arguments);
        entityManager.clear();
    }

    private static String report(String title) {
        return """
                {"title": "%s", "description": "The lamp is off since Monday.",
                 "location": {"latitude": 36.7213, "longitude": -4.4214}}
                """
                .formatted(title);
    }

    private ResultActions report(UUID reporter, String key, String body) throws Exception {
        return mvc.perform(
                post(INCIDENTS)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(reporter, Role.CITIZEN))
                        .header(IDEMPOTENCY_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static MvcResult created(ResultActions result) throws Exception {
        return result.andExpect(status().isCreated()).andReturn();
    }

    private static UUID id(MvcResult result) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private int incidentsOf(UUID reporter) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM incident WHERE reporter_id = ?",
                        Integer.class,
                        reporter);
        return count == null ? 0 : count;
    }

    private int historyOf(UUID incident) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM status_change WHERE incident_id = ?",
                        Integer.class,
                        incident);
        return count == null ? 0 : count;
    }

    private UUID citizen() {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                UUID.class,
                "citizen-" + UUID.randomUUID() + "@example.com");
    }
}
