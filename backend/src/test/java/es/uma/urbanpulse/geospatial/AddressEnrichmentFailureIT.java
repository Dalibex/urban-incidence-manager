package es.uma.urbanpulse.geospatial;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.geospatial.service.AddressEnrichmentWorker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Failure and limit cases of #375 for #378, against PostgreSQL and a fake Nominatim with a script
 * of answers: a slow provider, {@code 5xx} and {@code 429} with bounded retries, answers that are
 * refused, and two runs at once. The happy path, {@code 503} and a position outside Málaga are in
 * {@link AddressEnrichmentIT}; the existing incidents, in {@link AddressMigrationIT}.
 *
 * <p>The pending locations are inserted with SQL as a report leaves them, each with its own report
 * time: the worker takes the oldest first, so the order of the calls is known.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AddressEnrichmentFailureIT {

    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;

    private static final String LARIOS =
            "{\"address\": {\"house_number\": \"5\", \"road\": \"Calle Larios\","
                    + " \"city\": \"Málaga\", \"postcode\": \"29005\", \"country_code\": \"es\"}}";
    private static final String TORREMOLINOS =
            "{\"address\": {\"road\": \"Calle San Miguel\", \"town\": \"Torremolinos\","
                    + " \"country_code\": \"es\"}}";

    // Short, so a slow answer passes it quickly.
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Duration SLOWER_THAN_THE_TIMEOUT = TIMEOUT.multipliedBy(3);

    private static final FakeNominatim NOMINATIM = new FakeNominatim();

    @DynamicPropertySource
    static void pointToTheFakeNominatim(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.address.nominatim-base-url", NOMINATIM::baseUrl);
        registry.add("urbanpulse.address.nominatim-timeout", TIMEOUT::toString);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AddressEnrichmentWorker worker;

    private final List<UUID> createdIncidents = new ArrayList<>();
    private UUID citizen;

    @BeforeEach
    void startClean() {
        NOMINATIM.reset();
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
        for (UUID incident : createdIncidents) {
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
    static void stopTheFakeNominatim() {
        NOMINATIM.close();
    }

    // #375, provider 5xx or 429: both keep the address pending, and the second failure in a row
    // stops the run, so an outage costs at most two calls per run. The next run takes them again.
    @Test
    void tooManyRequestsAndServerErrorsKeepItPendingAndTwoInARowStopTheRun() {
        UUID first = pendingIncident(minutesAgo(30));
        UUID second = pendingIncident(minutesAgo(20));
        UUID third = pendingIncident(minutesAgo(10));
        NOMINATIM.answerNext(answer(429, "{}"), answer(503, "{}"));

        worker.resolvePending();

        assertThat(NOMINATIM.calls()).isEqualTo(2);
        for (UUID incident : List.of(first, second, third)) {
            assertThat(address(incident))
                    .containsEntry("address_status", "PENDING")
                    .containsEntry("address", null);
        }

        worker.resolvePending();

        assertThat(NOMINATIM.calls()).isEqualTo(5);
        for (UUID incident : List.of(first, second, third)) {
            assertThat(address(incident)).containsEntry("address_status", "AVAILABLE");
        }
    }

    // #375, provider slow: an answer past the timeout leaves the address pending for the next run,
    // and the incident as it was; one failure does not stop the run, so the next one is resolved.
    @Test
    void aSlowAnswerLeavesItPendingAndTheRunGoesOn() {
        UUID older = pendingIncident(minutesAgo(20));
        UUID newer = pendingIncident(minutesAgo(10));
        Map<String, Object> olderBefore = incidentRow(older);
        NOMINATIM.answerNext(answer(200, LARIOS).after(SLOWER_THAN_THE_TIMEOUT));

        worker.resolvePending();

        assertThat(address(older)).containsEntry("address_status", "PENDING");
        assertThat(incidentRow(older)).isEqualTo(olderBefore);
        assertThat(address(newer)).containsEntry("address_status", "AVAILABLE");

        worker.resolvePending();

        assertThat(address(older)).containsEntry("address_status", "AVAILABLE");
    }

    // An answer the source would give again (a 400, a body that cannot be read, JSON without an
    // address) ends the address as unavailable, with no value and no retry; Nominatim's
    // "Unable to geocode", where it knows no place, is outside Málaga.
    @Test
    void aRefusedOrUnreadableAnswerEndsItWithoutAValueAndIsNotRetried() {
        UUID refused = pendingIncident(minutesAgo(40));
        UUID unreadable = pendingIncident(minutesAgo(30));
        UUID withoutAddress = pendingIncident(minutesAgo(20));
        UUID noPlace = pendingIncident(minutesAgo(10));
        List<UUID> all = List.of(refused, unreadable, withoutAddress, noPlace);
        Map<UUID, Map<String, Object>> before = new HashMap<>();
        all.forEach(incident -> before.put(incident, incidentRow(incident)));
        NOMINATIM.answerNext(
                answer(400, "{}"),
                answer(200, "{not json"),
                answer(200, "{\"place_id\": 1}"),
                answer(200, "{\"error\": \"Unable to geocode\"}"));

        worker.resolvePending();

        assertThat(address(refused)).containsEntry("address_status", "UNAVAILABLE");
        assertThat(address(unreadable)).containsEntry("address_status", "UNAVAILABLE");
        assertThat(address(withoutAddress)).containsEntry("address_status", "UNAVAILABLE");
        assertThat(address(noPlace)).containsEntry("address_status", "OUTSIDE_AREA");
        for (UUID incident : all) {
            assertThat(address(incident))
                    .containsEntry("address", null)
                    .containsEntry("address_source", null)
                    .containsEntry("address_resolved_at", null);
            assertThat(incidentRow(incident)).isEqualTo(before.get(incident));
        }

        worker.resolvePending();

        assertThat(NOMINATIM.calls()).isEqualTo(4);
    }

    // Two runs at once (two replicas) take the same pending address and both get an answer, here
    // a different one each. Only one write wins, and the row is a whole answer, never half of each.
    @Test
    void twoRunsAtOnceStoreOneWholeAnswer() {
        UUID incident = pendingIncident(minutesAgo(5));
        NOMINATIM.answerNext(answer(200, LARIOS), answer(200, TORREMOLINOS));
        // Neither call is answered until both runs have asked, so both took it as pending.
        NOMINATIM.holdAnswersUntil(2);

        CompletableFuture.allOf(
                        CompletableFuture.runAsync(worker::resolvePending),
                        CompletableFuture.runAsync(worker::resolvePending))
                .join();

        assertThat(NOMINATIM.calls()).isEqualTo(2);
        Map<String, Object> row = address(incident);
        assertThat(row.get("address_status")).isIn("AVAILABLE", "OUTSIDE_AREA");
        if ("AVAILABLE".equals(row.get("address_status"))) {
            assertThat(row)
                    .containsEntry("address", "Calle Larios, 5, 29005 Málaga")
                    .containsEntry("address_source", "OpenStreetMap");
            assertThat(row.get("address_resolved_at")).isNotNull();
        } else {
            assertThat(row)
                    .containsEntry("address", null)
                    .containsEntry("address_source", null)
                    .containsEntry("address_resolved_at", null);
        }
    }

    /**
     * A location as a report leaves it, with the address pending, reported at {@code reportedAt}.
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
        return incident;
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    private Map<String, Object> address(UUID incident) {
        return jdbc.queryForMap(
                "SELECT address, address_status, address_source, address_resolved_at"
                        + " FROM location WHERE incident_id = ?",
                incident);
    }

    /** What the worker must never change in the incident. */
    private Map<String, Object> incidentRow(UUID incident) {
        return jdbc.queryForMap(
                "SELECT i.status, i.category_id, i.priority, i.version, i.updated_at,"
                        + " (SELECT count(*) FROM status_change s WHERE s.incident_id = i.id)"
                        + " AS history FROM incident i WHERE i.id = ?",
                incident);
    }

    private static Answer answer(int status, String body) {
        return new Answer(status, body, Duration.ZERO);
    }

    private record Answer(int status, String body, Duration delay) {
        Answer after(Duration wait) {
            return new Answer(status, body, wait);
        }
    }

    /**
     * A fake Nominatim with the JDK {@code HttpServer}: each call takes the next scripted answer,
     * or Calle Larios when the script is empty. One thread per call, so two runs at once are
     * answered at once.
     */
    private static final class FakeNominatim implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService threads = Executors.newCachedThreadPool();
        private final Queue<Answer> script = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch gate;

        FakeNominatim() {
            try {
                server =
                        HttpServer.create(
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("The fake Nominatim could not start", e);
            }
            server.createContext("/", this::answer);
            server.setExecutor(threads);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            script.clear();
            calls.set(0);
            gate = null;
        }

        void answerNext(Answer... answers) {
            script.addAll(List.of(answers));
        }

        /** No call is answered until {@code count} calls have arrived, or 5 s have passed. */
        void holdAnswersUntil(int count) {
            gate = new CountDownLatch(count);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public void close() {
            server.stop(0);
            threads.shutdownNow();
        }

        private void answer(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            try {
                CountDownLatch waiting = gate;
                if (waiting != null) {
                    waiting.countDown();
                    waiting.await(5, TimeUnit.SECONDS);
                }
                Answer next = script.poll();
                if (next == null) {
                    next = AddressEnrichmentFailureIT.answer(200, LARIOS);
                }
                Thread.sleep(next.delay().toMillis());
                byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(next.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException e) {
                // The client gave up first (a timeout): there is nobody left to answer.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }
    }
}
