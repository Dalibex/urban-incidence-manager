package es.uma.urbanpulse.geospatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.uma.urbanpulse.TestHistories;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.geospatial.service.AddressEnrichmentWorker;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests of #378 for the address of #375 (T1 to T3), against PostgreSQL and a fake
 * Nominatim on the loopback interface: no test reaches the real one. Each run of the worker is
 * started by the test ({@code resolvePending()}), as the scheduled one is off in the tests. T4, the
 * screens, is in the frontend tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class})
class AddressEnrichmentIT {

    private static final String INCIDENTS = "/api/v1/incidents";

    // Calle Larios, Málaga: latitude and longitude are far apart, so a swap shows.
    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;

    private static final String LARIOS =
            "{\"address\": {\"house_number\": \"5\", \"road\": \"Calle Larios\","
                    + " \"city\": \"Málaga\", \"postcode\": \"29005\", \"country_code\": \"es\"}}";
    private static final String TORREMOLINOS =
            "{\"address\": {\"road\": \"Calle San Miguel\", \"town\": \"Torremolinos\","
                    + " \"country_code\": \"es\"}}";

    // The urbanpulse.address.max-delay of application.yaml, which the tests do not change.
    private static final Duration MAX_DELAY = Duration.ofHours(24);

    private static final FakeNominatim NOMINATIM = new FakeNominatim();

    @DynamicPropertySource
    static void pointToTheFakeNominatim(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.address.nominatim-base-url", NOMINATIM::baseUrl);
        registry.add("urbanpulse.address.nominatim-timeout", () -> "PT0.5S");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @Autowired private AddressEnrichmentWorker worker;

    private final List<UUID> createdIncidents = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();
    private UUID citizen;
    private UUID operator;

    @BeforeEach
    void startClean() {
        NOMINATIM.reset();
        citizen = user(Role.CITIZEN);
        operator = user(Role.MUNICIPAL_OPERATOR);
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        for (UUID incident : createdIncidents) {
            jdbc.update("DELETE FROM urban_context WHERE incident_id = ?", incident);
            TestHistories.delete(jdbc, "incident_id = ?", incident);
            jdbc.update("DELETE FROM location WHERE incident_id = ?", incident);
            jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        }
        for (UUID user : createdUsers) {
            jdbc.update(
                    "DELETE FROM users u WHERE u.id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.actor_id = u.id)",
                    user);
        }
    }

    @AfterAll
    static void stopTheFakeNominatim() {
        NOMINATIM.close();
    }

    // T1: the report is saved with the address pending, and the next run of the worker stores the
    // normalized address with its source and the instant it was stored. Staff and the citizen see
    // it in the detail, staff in the listing too.
    @Test
    void aReportGetsItsNormalizedAddressAfterItIsSaved() throws Exception {
        NOMINATIM.answerEveryCall(200, LARIOS);
        UUID incident = report();
        assertThat(address(incident)).containsEntry("address_status", "PENDING");
        assertThat(NOMINATIM.calls()).isZero();

        // PostgreSQL keeps microseconds.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        worker.resolvePending();
        Instant after = Instant.now();

        Map<String, Object> row = address(incident);
        assertThat(row)
                .containsEntry("address_status", "AVAILABLE")
                .containsEntry("address", "Calle Larios, 5, 29005 Málaga")
                .containsEntry("address_source", "OpenStreetMap");
        assertThat(((Timestamp) row.get("address_resolved_at")).toInstant())
                .isBetween(before, after);
        assertThat(NOMINATIM.lastQuery()).contains("lat=" + LATITUDE).contains("lon=" + LONGITUDE);

        mvc.perform(get(INCIDENTS + "/" + incident).header(HttpHeaders.AUTHORIZATION, staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.address").value("Calle Larios, 5, 29005 Málaga"))
                .andExpect(jsonPath("$.location.addressStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.location.addressSource.name").value("OpenStreetMap"))
                .andExpect(
                        jsonPath("$.location.addressSource.attribution")
                                .value("© OpenStreetMap contributors"));
        mvc.perform(get(INCIDENTS + "/" + incident).header(HttpHeaders.AUTHORIZATION, citizen()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.address").value("Calle Larios, 5, 29005 Málaga"))
                .andExpect(jsonPath("$.location.addressStatus").value("AVAILABLE"));
        mvc.perform(get(INCIDENTS).header(HttpHeaders.AUTHORIZATION, staff()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + incident + "')].address")
                                .value(Matchers.contains("Calle Larios, 5, 29005 Málaga")))
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + incident + "')].addressStatus")
                                .value(Matchers.contains("AVAILABLE")));
        // The citizen's listing has no address field at all (docs/api.md, "Absent and null").
        mvc.perform(get(INCIDENTS).header(HttpHeaders.AUTHORIZATION, citizen()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].address").doesNotExist())
                .andExpect(jsonPath("$.content[0].addressStatus").doesNotExist());
    }

    // T2: with Nominatim answering 503 the report is still accepted at once, and the worker never
    // runs inside it; the address stays pending and is retried on each run, until the report is
    // older than that maximum delay: then it is unavailable, without another call. The incident is
    // never touched.
    @Test
    void withTheSourceDownTheReportIsAcceptedAndTheAddressPendingThenUnavailable()
            throws Exception {
        NOMINATIM.answerEveryCall(503, "{}");

        UUID incident = report();
        assertThat(NOMINATIM.calls()).isZero();
        assertThat(address(incident)).containsEntry("address_status", "PENDING");
        Map<String, Object> incidentBefore = incidentRow(incident);

        worker.resolvePending();
        assertThat(NOMINATIM.calls()).isEqualTo(1);
        assertThat(address(incident)).containsEntry("address_status", "PENDING");
        mvc.perform(get(INCIDENTS + "/" + incident).header(HttpHeaders.AUTHORIZATION, citizen()))
                .andExpect(jsonPath("$.location.address").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.location.addressStatus").value("PENDING"))
                .andExpect(jsonPath("$.location.addressSource").value(Matchers.nullValue()));

        worker.resolvePending();
        assertThat(NOMINATIM.calls()).isEqualTo(2);
        assertThat(address(incident)).containsEntry("address_status", "PENDING");

        reportedAgo(incident, MAX_DELAY.plusMinutes(1));
        worker.resolvePending();
        assertThat(NOMINATIM.calls()).isEqualTo(2);
        assertThat(address(incident))
                .containsEntry("address_status", "UNAVAILABLE")
                .containsEntry("address", null)
                .containsEntry("address_source", null);
        mvc.perform(get(INCIDENTS + "/" + incident).header(HttpHeaders.AUTHORIZATION, staff()))
                .andExpect(jsonPath("$.location.addressStatus").value("UNAVAILABLE"));
        assertThat(incidentRow(incident))
                .usingRecursiveComparison()
                .ignoringFields("reported_at")
                .isEqualTo(incidentBefore);
    }

    // T3: a position outside Málaga gets no address, and the API says why.
    @Test
    void aPositionOutsideMalagaHasNoAddressAndSaysSo() throws Exception {
        NOMINATIM.answerEveryCall(200, TORREMOLINOS);
        UUID incident = report();

        worker.resolvePending();

        assertThat(address(incident))
                .containsEntry("address_status", "OUTSIDE_AREA")
                .containsEntry("address", null);
        mvc.perform(get(INCIDENTS + "/" + incident).header(HttpHeaders.AUTHORIZATION, citizen()))
                .andExpect(jsonPath("$.location.address").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.location.addressStatus").value("OUTSIDE_AREA"));
    }

    // A second run finds nothing pending: an address is resolved once.
    @Test
    void aResolvedAddressIsNotAskedForAgain() throws Exception {
        NOMINATIM.answerEveryCall(200, LARIOS);
        report();

        worker.resolvePending();
        worker.resolvePending();

        assertThat(NOMINATIM.calls()).isEqualTo(1);
    }

    private UUID user(Role role) {
        UUID id =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test user', ?, '{bcrypt}not-used', ?) RETURNING id",
                        UUID.class,
                        "user-" + UUID.randomUUID() + "@example.com",
                        role.name());
        createdUsers.add(id);
        return id;
    }

    private String citizen() {
        return tokens.bearer(citizen, Role.CITIZEN);
    }

    private String staff() {
        return tokens.bearer(operator, Role.MUNICIPAL_OPERATOR);
    }

    private UUID report() throws Exception {
        String body =
                mvc.perform(
                                post(INCIDENTS)
                                        .header(HttpHeaders.AUTHORIZATION, citizen())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"title": "Broken streetlight",
                                                 "description": "The lamp is off since Monday.",
                                                 "location": {"latitude": %s,
                                                              "longitude": %s}}
                                                """
                                                        .formatted(LATITUDE, LONGITUDE)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.location.addressStatus").value("PENDING"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID incident = UUID.fromString(JsonPath.read(body, "$.id"));
        createdIncidents.add(incident);
        return incident;
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
                        + " i.reported_at,"
                        + " (SELECT count(*) FROM status_change s WHERE s.incident_id = i.id)"
                        + " AS history FROM incident i WHERE i.id = ?",
                incident);
    }

    private void reportedAgo(UUID incident, Duration age) {
        jdbc.update(
                "UPDATE incident SET reported_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)),
                incident);
    }

    /**
     * A fake Nominatim with the JDK {@code HttpServer}: every call takes the same scripted answer
     * and the server keeps the query of the last one.
     */
    private static final class FakeNominatim implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();
        private final Queue<String> queries = new ConcurrentLinkedQueue<>();
        private volatile int status;
        private volatile String body;

        FakeNominatim() {
            try {
                server =
                        HttpServer.create(
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("The fake Nominatim could not start", e);
            }
            server.createContext("/", this::answer);
            server.start();
            reset();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            calls.set(0);
            queries.clear();
            answerEveryCall(200, LARIOS);
        }

        void answerEveryCall(int status, String body) {
            this.status = status;
            this.body = body;
        }

        int calls() {
            return calls.get();
        }

        String lastQuery() {
            String last = null;
            for (String query : queries) {
                last = query;
            }
            return last;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void answer(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
