package es.uma.urbanpulse.urbancontext;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.urbancontext.service.WeatherEnrichmentWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T14 of #309 for #307, in its own class because it needs a longer Open-Meteo timeout than {@link
 * WeatherEnrichmentIT}: the fake holds both answers until both runs have asked, so the timeout must
 * not fire while the second run arrives, even on a busy machine. {@code WeatherEnrichmentIT} keeps
 * its 500 ms timeout because T9 relies on it firing quickly; a Spring context per timeout is the
 * least invasive way to have both.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WeatherEnrichmentConcurrencyIT {

    // Well above the time two runs need to reach the fake, and below its 5 s cap on the gate.
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final FakeOpenMeteo OPEN_METEO = FakeOpenMeteo.start();

    @DynamicPropertySource
    static void pointToTheFakeOpenMeteo(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.weather.open-meteo-base-url", () -> OPEN_METEO.baseUrl());
        registry.add("urbanpulse.weather.open-meteo-timeout", TIMEOUT::toString);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WeatherEnrichmentWorker worker;
    @Autowired private MeterRegistry meters;

    private UUID citizen;
    private UUID incident;

    @BeforeEach
    void startClean() {
        OPEN_METEO.reset();
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
        if (incident != null) {
            jdbc.update(
                    "DELETE FROM external_observation WHERE urban_context_id IN"
                            + " (SELECT id FROM urban_context WHERE incident_id = ?)",
                    incident);
            jdbc.update("DELETE FROM urban_context WHERE incident_id = ?", incident);
            TestHistories.delete(jdbc, "incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    @AfterAll
    static void stopTheFakeOpenMeteo() {
        OPEN_METEO.close();
    }

    // T14: two runs at once take the same pending incident and both get Open-Meteo's answer, but
    // only one writes it: one context and one set of observations, and one COMPLETE counted.
    @Test
    void twoRunsAtOnceStoreOneContextAndOneSetOfObservations() {
        incident = pendingIncident();
        double completeBefore = completeContexts();
        double answeredBefore = answeredCalls();
        // Neither call is answered until both runs have asked, so both took it as PENDING.
        OPEN_METEO.holdAnswersUntil(2);

        CompletableFuture.allOf(
                        CompletableFuture.runAsync(worker::enrichPending),
                        CompletableFuture.runAsync(worker::enrichPending))
                .join();

        assertThat(OPEN_METEO.calls()).isEqualTo(2);
        // Both calls were answered within the client's timeout, so both runs really raced to
        // write it; a timed-out call would leave the other run alone.
        assertThat(answeredCalls() - answeredBefore).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM urban_context WHERE incident_id = ?",
                                Integer.class,
                                incident))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM urban_context WHERE incident_id = ?",
                                String.class,
                                incident))
                .isEqualTo("COMPLETE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM external_observation o"
                                        + " JOIN urban_context c ON c.id = o.urban_context_id"
                                        + " WHERE c.incident_id = ?",
                                Integer.class,
                                incident))
                .isEqualTo(3);
        // The run that lost the race does not count its context as completed too.
        assertThat(completeContexts() - completeBefore).isEqualTo(1);
    }

    /** An incident as a report leaves it, with its {@code PENDING} context, reported 5 min ago. */
    private UUID pendingIncident() {
        Timestamp reportedAt = Timestamp.from(Instant.now().minus(Duration.ofMinutes(5)));
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at) VALUES (?, 'Broken streetlight',"
                                + " 'The lamp is off.', 'REPORTED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        reportedAt,
                        reportedAt);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(-4.4214, 36.7213), 4326)::geography)",
                id);
        jdbc.update("INSERT INTO urban_context (incident_id, status) VALUES (?, 'PENDING')", id);
        return id;
    }

    private double completeContexts() {
        Counter complete =
                meters.find("urbanpulse.weather.contexts").tag("status", "COMPLETE").counter();
        return complete == null ? 0 : complete.count();
    }

    /** Calls to Open-Meteo that got an answer, from the metric of the worker (ADR-0018). */
    private double answeredCalls() {
        Timer answered =
                meters.find("urbanpulse.weather.requests").tag("outcome", "answered").timer();
        return answered == null ? 0 : answered.count();
    }
}
