package es.uma.urbanpulse.urbancontext;

import static es.uma.urbanpulse.urbancontext.FakeOpenMeteo.body;
import static es.uma.urbanpulse.urbancontext.FakeOpenMeteo.current;
import static es.uma.urbanpulse.urbancontext.FakeOpenMeteo.httpStatus;
import static es.uma.urbanpulse.urbancontext.FakeOpenMeteo.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.urbancontext.service.WeatherEnrichmentWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests of #309 for the weather enrichment of #307 (T1 to T4 and T9 to T13), against
 * PostgreSQL and a fake Open-Meteo ({@link FakeOpenMeteo}); T8 and T15, with Open-Meteo really
 * unreachable, are in {@link WeatherEnrichmentWithoutOpenMeteoIT}, and T14, which needs a longer
 * timeout, in {@link WeatherEnrichmentConcurrencyIT}.
 *
 * <p>They are written from #309 and ADR-0018 (<i>Mechanism</i>, <i>Outcomes</i>,
 * <i>Provenance</i>), not from the implementation: incidents are reported through HTTP or inserted
 * with SQL as a report leaves them, each run of the worker is started by the test ({@code
 * enrichPending()}), as the scheduled one is off in the tests, and the result is read with SQL. The
 * unit tests of #307 already cover the mapping of every WMO code (T2), the client and the worker
 * with mocks; here the queries, the transactions and the answers go through for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, WeatherEnrichmentIT.Clocks.class})
class WeatherEnrichmentIT {

    private static final String INCIDENTS = "/api/v1/incidents";

    // Calle Larios, Málaga: latitude and longitude are far apart, so a swap shows.
    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;

    // Short, so a slow answer passes it quickly; the default is 2 s (ADR-0018).
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Duration SLOWER_THAN_THE_TIMEOUT = TIMEOUT.multipliedBy(3);
    // The default WEATHER_MAX_DELAY of ADR-0018, which the tests do not change.
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    private static final FakeOpenMeteo OPEN_METEO = FakeOpenMeteo.start();

    @DynamicPropertySource
    static void pointToTheFakeOpenMeteo(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.weather.open-meteo-base-url", () -> OPEN_METEO.baseUrl());
        registry.add("urbanpulse.weather.open-meteo-timeout", TIMEOUT::toString);
    }

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
    void startClean() {
        OPEN_METEO.reset();
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
            jdbc.update(
                    "DELETE FROM external_observation WHERE urban_context_id IN"
                            + " (SELECT id FROM urban_context WHERE incident_id = ?)",
                    incident);
            jdbc.update("DELETE FROM urban_context WHERE incident_id = ?", incident);
            TestHistories.delete(jdbc, "incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        jdbc.update(
                "DELETE FROM users u WHERE u.id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                citizen);
    }

    @AfterAll
    static void stopTheFakeOpenMeteo() {
        OPEN_METEO.close();
    }

    // T1: a citizen reports; the context waits as PENDING, and the next run of the worker asks
    // Open-Meteo for the position of the report and stores RAINY (WMO 61) with its time.
    @Test
    void aReportIsEnrichedWithItsWeather() throws Exception {
        UUID incident = report();
        assertThat(context(incident)).containsEntry("status", "PENDING");
        assertThat(OPEN_METEO.calls()).isZero();
        double completeBefore = completeContexts();
        long delaysBefore = delaysRecorded();

        // PostgreSQL keeps microseconds.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        worker.enrichPending();
        Instant after = Instant.now();

        Map<String, Object> context = context(incident);
        assertThat(context).containsEntry("status", "COMPLETE").containsEntry("weather", "RAINY");
        assertThat(instant(context.get("captured_at"))).isBetween(before, after);
        assertThat(OPEN_METEO.queries())
                .singleElement()
                .satisfies(
                        query ->
                                assertThat(query)
                                        .contains("latitude=" + LATITUDE)
                                        .contains("longitude=" + LONGITUDE));
        // The metrics are recorded once the result is stored (ADR-0018, Observability).
        assertThat(completeContexts() - completeBefore).isEqualTo(1);
        assertThat(delaysRecorded() - delaysBefore).isEqualTo(1);
    }

    // T2, end to end: a clear sky with wind of 40 km/h or more is stored as WINDY. Every code of
    // the
    // table is covered by WeatherMappingTest.
    @Test
    void aClearSkyWithStrongWindIsStoredAsWindy() throws Exception {
        UUID incident = report();
        OPEN_METEO.answerNext(current(0, 45.0, 0.0));

        worker.enrichPending();

        assertThat(context(incident))
                .containsEntry("status", "COMPLETE")
                .containsEntry("weather", "WINDY");
    }

    // T3: one VALID observation per raw value, from Open-Meteo, with its unit; the observation
    // instant is the one Open-Meteo gives, the ingestion instant the one the backend stored it, and
    // the quality and the expiration stay empty (ADR-0018, Provenance).
    @Test
    void eachRawValueIsKeptWithItsProvenance() throws Exception {
        UUID incident = report();
        OPEN_METEO.answerNext(current(61, 12.5, 0.4));

        worker.enrichPending();

        Instant capturedAt = instant(context(incident).get("captured_at"));
        Map<String, Map<String, Object>> byType =
                observations(incident).stream()
                        .collect(
                                Collectors.toMap(
                                        row -> (String) row.get("measurement_type"), row -> row));
        assertThat(byType).containsOnlyKeys("WEATHER_CODE", "WIND_SPEED", "PRECIPITATION");
        assertThat(byType.get("WEATHER_CODE"))
                .containsEntry("value", "61")
                .containsEntry("unit", "WMO");
        assertThat(byType.get("WIND_SPEED")).containsEntry("unit", "km/h");
        assertThat(Double.parseDouble((String) byType.get("WIND_SPEED").get("value")))
                .isEqualTo(12.5);
        assertThat(byType.get("PRECIPITATION")).containsEntry("unit", "mm");
        assertThat(Double.parseDouble((String) byType.get("PRECIPITATION").get("value")))
                .isEqualTo(0.4);
        assertThat(byType.values())
                .allSatisfy(
                        row -> {
                            assertThat(row)
                                    .containsEntry("source", "Open-Meteo")
                                    .containsEntry("status", "VALID")
                                    .containsEntry("quality", null)
                                    .containsEntry("expiration_date", null);
                            assertThat(instant(row.get("observation_instant")))
                                    .isEqualTo(Instant.parse(FakeOpenMeteo.OBSERVED_AT + ":00Z"));
                            assertThat(instant(row.get("ingestion_date"))).isEqualTo(capturedAt);
                        });
    }

    // T3: a value Open-Meteo does not send is not stored, never as zero (ADR-0018, Source).
    @Test
    void aMissingWindIsNotStored() throws Exception {
        UUID incident = report();
        OPEN_METEO.answerNext(json("\"weather_code\": 3, \"precipitation\": 0.0"));

        worker.enrichPending();

        assertThat(context(incident)).containsEntry("weather", "CLOUDY");
        assertThat(observations(incident))
                .extracting(row -> row.get("measurement_type"))
                .containsExactlyInAnyOrder("WEATHER_CODE", "PRECIPITATION");
    }

    // T4: the report does not wait for Open-Meteo: with Open-Meteo slower than its timeout, POST
    // /incidents makes no call to it, so nothing in its transaction can wait. No wall-clock time is
    // measured: on a busy CI runner that would fail for reasons unrelated to Open-Meteo.
    @Test
    void theReportDoesNotWaitForOpenMeteo() throws Exception {
        OPEN_METEO.answerEveryCall(current(61, 10.0, 0.4).after(SLOWER_THAN_THE_TIMEOUT));

        UUID incident = report();

        assertThat(OPEN_METEO.calls()).isZero();
        assertThat(context(incident)).containsEntry("status", "PENDING");
    }

    // T9: a call past the timeout leaves its context PENDING for the next run, and the incident
    // itself as it was; one failure does not stop the run: the next pending incident is still
    // enriched.
    @Test
    void aTimeoutLeavesTheContextPendingAndTheRunGoesOn() {
        UUID older = pendingIncident(minutesAgo(20));
        UUID newer = pendingIncident(minutesAgo(10));
        Map<String, Object> olderBefore = incidentRow(older);
        OPEN_METEO.answerNext(current(61, 10.0, 0.4).after(SLOWER_THAN_THE_TIMEOUT));

        worker.enrichPending();

        assertThat(context(older))
                .containsEntry("status", "PENDING")
                .containsEntry("weather", null);
        assertThat(observations(older)).isEmpty();
        assertThat(incidentRow(older)).isEqualTo(olderBefore);
        assertThat(context(newer)).containsEntry("status", "COMPLETE");

        worker.enrichPending();

        assertThat(context(older)).containsEntry("status", "COMPLETE");
    }

    // T10: 503 and then 429 keep the contexts PENDING, and the second failure in a row stops the
    // run: while Open-Meteo fails, a run makes at most two calls. The next run takes them again.
    @Test
    void twoFailuresInARowStopTheRunAndTheContextsWait() {
        UUID first = pendingIncident(minutesAgo(30));
        UUID second = pendingIncident(minutesAgo(20));
        UUID third = pendingIncident(minutesAgo(10));
        Map<UUID, Map<String, Object>> before = new HashMap<>();
        List.of(first, second, third)
                .forEach(incident -> before.put(incident, incidentRow(incident)));
        OPEN_METEO.answerNext(httpStatus(503), httpStatus(429));

        worker.enrichPending();

        assertThat(OPEN_METEO.calls()).isEqualTo(2);
        for (UUID incident : List.of(first, second, third)) {
            assertThat(context(incident)).containsEntry("status", "PENDING");
            assertThat(observations(incident)).isEmpty();
            assertThat(incidentRow(incident)).isEqualTo(before.get(incident));
        }

        worker.enrichPending();

        assertThat(OPEN_METEO.calls()).isEqualTo(5);
        for (UUID incident : List.of(first, second, third)) {
            assertThat(context(incident)).containsEntry("status", "COMPLETE");
            assertThat(incidentRow(incident)).isEqualTo(before.get(incident));
        }
    }

    // T11: an answer without a WMO code or with a code outside the table is UNAVAILABLE; a body
    // that cannot be read or a 400 is ERROR. None stores a value, none changes the incident, and
    // none is retried.
    @Test
    void aMalformedAnswerNeverStoresAValue() {
        UUID withoutCode = pendingIncident(minutesAgo(40));
        UUID unknownCode = pendingIncident(minutesAgo(30));
        UUID unreadable = pendingIncident(minutesAgo(20));
        UUID refused = pendingIncident(minutesAgo(10));
        List<UUID> all = List.of(withoutCode, unknownCode, unreadable, refused);
        Map<UUID, Map<String, Object>> before = new HashMap<>();
        all.forEach(incident -> before.put(incident, incidentRow(incident)));
        OPEN_METEO.answerNext(
                json("\"wind_speed_10m\": 5.0, \"precipitation\": 0.0"),
                current(4, 5.0, 0.0),
                body("{not json"),
                httpStatus(400));

        worker.enrichPending();

        assertThat(context(withoutCode)).containsEntry("status", "UNAVAILABLE");
        assertThat(context(unknownCode)).containsEntry("status", "UNAVAILABLE");
        assertThat(context(unreadable)).containsEntry("status", "ERROR");
        assertThat(context(refused)).containsEntry("status", "ERROR");
        for (UUID incident : all) {
            assertThat(context(incident))
                    .containsEntry("weather", null)
                    .containsEntry("captured_at", null);
            assertThat(observations(incident)).isEmpty();
            assertThat(incidentRow(incident)).isEqualTo(before.get(incident));
        }

        worker.enrichPending();

        assertThat(OPEN_METEO.calls()).isEqualTo(4);
    }

    // T12: once the report is older than WEATHER_MAX_DELAY, the context is UNAVAILABLE without
    // calling Open-Meteo: the current weather is not stored as the weather at the report.
    @Test
    void aLateRunEndsTheContextUnavailableWithoutCallingOpenMeteo() throws Exception {
        UUID incident = report();
        clock.advance(MAX_DELAY.plusMinutes(1));

        worker.enrichPending();

        assertThat(OPEN_METEO.calls()).isZero();
        assertThat(context(incident))
                .containsEntry("status", "UNAVAILABLE")
                .containsEntry("weather", null)
                .containsEntry("captured_at", null);
        assertThat(observations(incident)).isEmpty();
    }

    // T12, the limit: a report just under WEATHER_MAX_DELAY is still enriched.
    @Test
    void aReportJustUnderTheMaximumDelayIsStillEnriched() throws Exception {
        UUID incident = report();
        clock.advance(MAX_DELAY.minusMinutes(1));

        worker.enrichPending();

        assertThat(context(incident)).containsEntry("status", "COMPLETE");
    }

    // T13: the pending work is rows in the database, not a list kept by the worker. The contexts
    // already PENDING when a run starts are enriched by it, each one once: the next run does not
    // call Open-Meteo again or store their observations twice. No restart is simulated.
    @Test
    void pendingRowsAlreadyInTheDatabaseAreEnrichedOnce() {
        UUID first = pendingIncident(minutesAgo(15));
        UUID second = pendingIncident(minutesAgo(5));

        worker.enrichPending();
        worker.enrichPending();

        assertThat(OPEN_METEO.calls()).isEqualTo(2);
        for (UUID incident : List.of(first, second)) {
            assertThat(context(incident)).containsEntry("status", "COMPLETE");
            assertThat(observations(incident)).hasSize(3);
        }
    }

    /** Contexts the worker ended as {@code COMPLETE}, from its metric (ADR-0018). */
    private double completeContexts() {
        Counter complete =
                meters.find("urbanpulse.weather.contexts").tag("status", "COMPLETE").counter();
        return complete == null ? 0 : complete.count();
    }

    /** Delays between a report and its weather recorded by the worker, from its metric. */
    private long delaysRecorded() {
        Timer delay = meters.find("urbanpulse.weather.delay").timer();
        return delay == null ? 0 : delay.count();
    }

    /**
     * What the worker must never change in the incident: its status, classification, version and
     * last change, and its history.
     */
    private Map<String, Object> incidentRow(UUID incident) {
        return jdbc.queryForMap(
                "SELECT i.status, i.category_id, i.priority, i.version, i.updated_at,"
                        + " (SELECT count(*) FROM status_change s WHERE s.incident_id = i.id)"
                        + " AS history FROM incident i WHERE i.id = ?",
                incident);
    }

    /** Reports an incident through the API as the citizen, and returns its id. */
    private UUID report() throws Exception {
        String body =
                mvc.perform(
                                post(INCIDENTS)
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(citizen, Role.CITIZEN))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"title": "Broken streetlight",
                                                 "description": "The lamp is off since Monday.",
                                                 "location": {"latitude": %s, "longitude": %s}}
                                                """
                                                        .formatted(LATITUDE, LONGITUDE)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID incident = UUID.fromString(JsonPath.read(body, "$.id"));
        createdIncidents.add(incident);
        return incident;
    }

    /**
     * An incident as a report leaves it, with its {@code PENDING} context, reported at {@code
     * reportedAt}: the worker takes the oldest first, so the order of the calls is known.
     */
    private UUID pendingIncident(Instant reportedAt) {
        UUID incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status,"
                                + " reported_at, updated_at) VALUES (?, 'Broken streetlight',"
                                + " 'The lamp is off.', 'REPORTED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(reportedAt),
                        Timestamp.from(reportedAt));
        createdIncidents.add(incident);
        jdbc.update(
                "INSERT INTO location (incident_id, coordinates) VALUES"
                        + " (?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)",
                incident,
                LONGITUDE,
                LATITUDE);
        jdbc.update(
                "INSERT INTO urban_context (incident_id, status) VALUES (?, 'PENDING')", incident);
        return incident;
    }

    private Instant minutesAgo(long minutes) {
        return Clock.systemUTC().instant().minus(Duration.ofMinutes(minutes));
    }

    private Map<String, Object> context(UUID incident) {
        return jdbc.queryForMap(
                "SELECT status, weather, captured_at FROM urban_context WHERE incident_id = ?",
                incident);
    }

    private List<Map<String, Object>> observations(UUID incident) {
        return jdbc.queryForList(
                "SELECT o.source, o.measurement_type, o.value, o.unit, o.quality,"
                        + " o.observation_instant, o.ingestion_date, o.expiration_date, o.status"
                        + " FROM external_observation o"
                        + " JOIN urban_context c ON c.id = o.urban_context_id"
                        + " WHERE c.incident_id = ?",
                incident);
    }

    private static Instant instant(Object timestamp) {
        if (timestamp instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        return ((Timestamp) timestamp).toInstant();
    }
}
