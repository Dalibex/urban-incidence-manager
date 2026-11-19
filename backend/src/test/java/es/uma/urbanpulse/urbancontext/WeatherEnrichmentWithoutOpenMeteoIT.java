package es.uma.urbanpulse.urbancontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.urbancontext.service.WeatherEnrichmentWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests of #309 for #307 with Open-Meteo really unreachable (T8 and T15): the backend
 * starts with the test configuration, whose {@code urbanpulse.weather.open-meteo-base-url} is a
 * closed port of the loopback interface, so every call is a refused connection, as with no network.
 * The rest of the cases, with a fake Open-Meteo, are in {@link WeatherEnrichmentIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    TestTokens.class,
    WeatherEnrichmentWithoutOpenMeteoIT.Clocks.class
})
class WeatherEnrichmentWithoutOpenMeteoIT {

    // The default WEATHER_MAX_DELAY of ADR-0018, which the tests do not change.
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private WeatherEnrichmentWorker worker;
    @Autowired private MutableClock clock;
    @Autowired private MeterRegistry meters;

    private final List<UUID> createdIncidents = new ArrayList<>();
    private UUID citizen;

    @BeforeEach
    void createCitizen() {
        clock.reset();
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test citizen', ?, '{bcrypt}not-used', 'CITIZEN')"
                                + " RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        clock.reset();
        for (UUID incident : createdIncidents) {
            jdbc.update("DELETE FROM urban_context WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM status_change WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update(
                "DELETE FROM users u WHERE u.id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                citizen);
    }

    // T15: the backend started with Open-Meteo unreachable (this test runs at all), reporting
    // works, and the context waits as PENDING.
    @Test
    void reportingWorksWithOpenMeteoUnreachable() throws Exception {
        UUID incident = report();

        assertThat(contextStatus(incident)).isEqualTo("PENDING");
    }

    // T8: a refused connection leaves the context PENDING, and every run tries it again until the
    // report is older than WEATHER_MAX_DELAY; then it is UNAVAILABLE without a call, with no value
    // stored. The incident is never touched.
    @Test
    void aRefusedConnectionRetriesUntilTheMaximumDelay() throws Exception {
        UUID incident = report();
        Map<String, Object> incidentBefore = incidentRow(incident);
        long unavailableBefore = unavailableCalls();

        worker.enrichPending();
        assertThat(contextStatus(incident)).isEqualTo("PENDING");
        assertThat(unavailableCalls() - unavailableBefore).isEqualTo(1);

        clock.advance(MAX_DELAY.minusMinutes(1));
        worker.enrichPending();
        assertThat(contextStatus(incident)).isEqualTo("PENDING");
        assertThat(unavailableCalls() - unavailableBefore).isEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        worker.enrichPending();
        assertThat(contextStatus(incident)).isEqualTo("UNAVAILABLE");
        // The expired context is closed without calling Open-Meteo.
        assertThat(unavailableCalls() - unavailableBefore).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM external_observation o"
                                        + " JOIN urban_context c ON c.id = o.urban_context_id"
                                        + " WHERE c.incident_id = ?",
                                Integer.class,
                                incident))
                .isZero();
        assertThat(incidentRow(incident)).isEqualTo(incidentBefore);
    }

    /** Calls that found Open-Meteo unreachable, from the metric of the worker (ADR-0018). */
    private long unavailableCalls() {
        Timer unavailable =
                meters.find("urbanpulse.weather.requests").tag("outcome", "unavailable").timer();
        return unavailable == null ? 0 : unavailable.count();
    }

    /** What the worker must never change in the incident. */
    private Map<String, Object> incidentRow(UUID incident) {
        return jdbc.queryForMap(
                "SELECT i.status, i.category_id, i.priority, i.version, i.updated_at,"
                        + " (SELECT count(*) FROM status_change s WHERE s.incident_id = i.id)"
                        + " AS history FROM incident i WHERE i.id = ?",
                incident);
    }

    private UUID report() throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/incidents")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(citizen, Role.CITIZEN))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"title": "Broken streetlight",
                                                 "description": "The lamp is off since Monday.",
                                                 "location": {"latitude": 36.7213,
                                                              "longitude": -4.4214}}
                                                """))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID incident = UUID.fromString(JsonPath.read(body, "$.id"));
        createdIncidents.add(incident);
        return incident;
    }

    private String contextStatus(UUID incident) {
        return jdbc.queryForObject(
                "SELECT status FROM urban_context WHERE incident_id = ?", String.class, incident);
    }
}
