package es.uma.urbanpulse.geospatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.uma.urbanpulse.ApiContract;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.geospatial.service.AddressSourceBusyException;
import es.uma.urbanpulse.geospatial.service.PlaceProvider;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The address search {@code listPlaces} of #179 (RF04, RF26), written from the contract ({@code
 * /places} in {@code docs/api/openapi.yml}): the answers, the validation of {@code q}, the roles,
 * the per-user limit, the cache and a provider that is down. Nominatim is a fake server of the JDK,
 * and every response is checked against the contract ({@link ApiContract}).
 *
 * <p>The cache of searches lives in the Spring context shared by the tests, so each test searches a
 * text of its own, and a limit per user is counted for a new citizen each time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTokens.class, ApiContract.EveryResponse.class})
@Transactional
// The search follows the switch of the address enrichment, off in the test profile. The worker it
// also turns on never runs here: its first run would be in a thousand hours.
@TestPropertySource(
        properties = {"urbanpulse.address.enabled=true", "urbanpulse.address.interval=PT1000H"})
class PlaceApiIT {

    private static final String PLACES = "/api/v1/places";

    private static final String LARIOS =
            "{\"display_name\": \"Calle Larios, Centro, Málaga, Andalucía, España\","
                    + " \"lat\": \"36.7213\", \"lon\": \"-4.4214\","
                    + " \"address\": {\"road\": \"Calle Larios\", \"city\": \"Málaga\","
                    + " \"country_code\": \"es\"}}";
    private static final String TORREMOLINOS =
            "{\"display_name\": \"Calle San Miguel, Torremolinos, España\","
                    + " \"lat\": \"36.62\", \"lon\": \"-4.50\","
                    + " \"address\": {\"road\": \"Calle San Miguel\", \"town\": \"Torremolinos\","
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

    @AfterAll
    static void stopTheFakeNominatim() {
        NOMINATIM.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;

    private UUID citizen;

    @BeforeEach
    void createCitizen() {
        NOMINATIM.reset();
        citizen = user(Role.CITIZEN);
    }

    @Test
    @DisplayName("answers the places of Málaga with their name and position")
    void answersThePlacesOfMalaga() throws Exception {
        NOMINATIM.body = "[" + LARIOS + "," + TORREMOLINOS + "]";

        var response = search(citizen, "Calle Larios " + unique()).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        var json = response.getContentAsString();
        assertThat((Integer) JsonPath.read(json, "$.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(json, "$[0].displayName"))
                .isEqualTo("Calle Larios, Centro, Málaga, Andalucía, España");
        assertThat((Double) JsonPath.read(json, "$[0].latitude")).isEqualTo(36.7213);
        assertThat((Double) JsonPath.read(json, "$[0].longitude")).isEqualTo(-4.4214);
    }

    @Test
    @DisplayName("answers 200 with an empty list when nothing matches")
    void answersAnEmptyListWhenNothingMatches() throws Exception {
        NOMINATIM.body = "[]";

        var response = search(citizen, "Nowhere " + unique()).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("[]");
    }

    @Test
    @DisplayName("answers 200 with an empty list when every match is outside Málaga")
    void answersAnEmptyListWhenEveryMatchIsOutsideMalaga() throws Exception {
        NOMINATIM.body = "[" + TORREMOLINOS + "]";

        var response = search(citizen, "San Miguel " + unique()).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("[]");
    }

    @Test
    @DisplayName("sends the text intact and bounded to Málaga to Nominatim")
    void sendsTheTextIntactToNominatim() throws Exception {
        NOMINATIM.body = "[]";
        var text = "Calle Álamos & Plaza " + unique();

        search(citizen, text);

        assertThat(NOMINATIM.lastQuery).contains("bounded=1").contains("countrycodes=es");
        assertThat(URLDecoder.decode(NOMINATIM.lastQuery, StandardCharsets.UTF_8))
                .contains("q=" + text);
    }

    @Test
    @DisplayName("strips the spaces around the text before searching")
    void stripsTheSpacesAroundTheText() throws Exception {
        NOMINATIM.body = "[]";
        var text = "Larios " + unique();

        search(citizen, "   " + text + "   ");

        assertThat(URLDecoder.decode(NOMINATIM.lastQuery, StandardCharsets.UTF_8))
                .contains("q=" + text + "&");
    }

    @Test
    @DisplayName("answers a repeated search from the cache: Nominatim is called once")
    void aRepeatedSearchCallsNominatimOnce() throws Exception {
        NOMINATIM.body = "[" + LARIOS + "]";
        var text = "Calle Larios " + unique();

        var first = search(citizen, text).andReturn().getResponse();
        var second = search(citizen, text).andReturn().getResponse();

        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(NOMINATIM.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("accepts a text of exactly 3 and of exactly 200 characters")
    void acceptsTheLimitsOfTheText() throws Exception {
        NOMINATIM.body = "[]";

        assertThat(search(citizen, "abc").andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(search(citizen, "b".repeat(200)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("rejects a request without q")
    void rejectsAMissingQ() throws Exception {
        expectMalformed(get(PLACES));
    }

    @Test
    @DisplayName("rejects an empty q")
    void rejectsAnEmptyQ() throws Exception {
        expectMalformed(get(PLACES).param("q", ""));
    }

    @Test
    @DisplayName("rejects a q that has fewer than 3 characters once stripped")
    void rejectsAQTooShort() throws Exception {
        expectMalformed(get(PLACES).param("q", "  ab  "));
    }

    @Test
    @DisplayName("rejects a q of 201 characters")
    void rejectsAQTooLong() throws Exception {
        expectMalformed(get(PLACES).param("q", "a".repeat(201)));
    }

    @Test
    @DisplayName("rejects a q repeated")
    void rejectsARepeatedQ() throws Exception {
        expectMalformed(get(PLACES).param("q", "Larios " + unique(), "Alameda " + unique()));
    }

    @Test
    @DisplayName("rejects an unknown parameter")
    void rejectsAnUnknownParameter() throws Exception {
        expectMalformed(get(PLACES).param("q", "Larios " + unique()).param("limit", "1"));
    }

    @Test
    @DisplayName("rejects a control character in q")
    void rejectsAControlCharacterInQ() throws Exception {
        expectMalformed(get(PLACES).param("q", "Larios\u0007" + unique()));
    }

    @Test
    @DisplayName("searches a q with a line break or a tab inside as one space")
    void searchesTheWhiteSpaceInsideQAsOneSpace() throws Exception {
        NOMINATIM.body = "[]";
        var street = "Calle " + unique();

        var response = search(citizen, street + "\r\n5,\t29005 Málaga").andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(URLDecoder.decode(NOMINATIM.lastQuery, StandardCharsets.UTF_8))
                .contains("q=" + street + " 5, 29005 Málaga&");
    }

    @Test
    @DisplayName("does not call Nominatim for a request it rejects")
    void doesNotCallNominatimForARejectedRequest() throws Exception {
        expectMalformed(get(PLACES).param("q", "ab"));

        assertThat(NOMINATIM.calls.get()).isZero();
    }

    @Test
    @DisplayName("answers 401 without a token")
    void answers401WithoutAToken() throws Exception {
        var response =
                mvc.perform(get(PLACES).param("q", "Larios " + unique())).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(NOMINATIM.calls.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"MUNICIPAL_OPERATOR", "TECHNICIAN", "ADMINISTRATOR", "ANALYST"})
    @DisplayName("answers 403 to every role but the citizen")
    void answers403ToTheOtherRoles(Role role) throws Exception {
        var response =
                mvc.perform(
                                get(PLACES)
                                        .param("q", "Larios " + unique())
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(user(role), role)))
                        .andReturn()
                        .getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(NOMINATIM.calls.get()).isZero();
    }

    @Test
    @DisplayName("answers 503 GEOCODING_UNAVAILABLE when Nominatim answers an error")
    void answers503WhenNominatimFails() throws Exception {
        NOMINATIM.status = 500;
        NOMINATIM.body = "{}";

        expectUnavailable(search(citizen, "Larios " + unique()));
    }

    @Test
    @DisplayName("answers 503 GEOCODING_UNAVAILABLE when Nominatim is slower than the timeout")
    void answers503WhenNominatimIsSlow() throws Exception {
        NOMINATIM.delay = SLOWER_THAN_THE_TIMEOUT;
        NOMINATIM.body = "[" + LARIOS + "]";

        expectUnavailable(search(citizen, "Larios " + unique()));
    }

    @Test
    @DisplayName("answers 503 GEOCODING_UNAVAILABLE when Nominatim answers what cannot be read")
    void answers503WhenTheAnswerCannotBeRead() throws Exception {
        NOMINATIM.body = "not json";

        expectUnavailable(search(citizen, "Larios " + unique()));
    }

    @Test
    @DisplayName("does not remember a failure: the next search asks Nominatim again")
    void aFailureIsNotCached() throws Exception {
        var text = "Larios " + unique();
        NOMINATIM.status = 500;
        NOMINATIM.body = "{}";
        expectUnavailable(search(citizen, text));

        NOMINATIM.status = 200;
        NOMINATIM.body = "[" + LARIOS + "]";
        var response = search(citizen, text).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(NOMINATIM.calls.get()).isEqualTo(2);
    }

    // RF26: the report never depends on the address search.
    @Test
    @DisplayName("still accepts a report while Nominatim is down")
    void aReportIsStillCreatedWhileNominatimIsDown() throws Exception {
        NOMINATIM.status = 500;
        NOMINATIM.body = "{}";
        expectUnavailable(search(citizen, "Larios " + unique()));

        var report =
                mvc.perform(
                                post("/api/v1/incidents")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                tokens.bearer(citizen, Role.CITIZEN))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"title": "Broken streetlight",
                                                 "description": "The lamp is off since Monday.",
                                                 "location": {"latitude": 36.7213,
                                                              "longitude": -4.4214}}
                                                """))
                        .andReturn()
                        .getResponse();

        assertThat(report.getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("answers 429 with Retry-After to the 11th search of a citizen in a minute")
    void answers429OnTheEleventhSearch() throws Exception {
        NOMINATIM.body = "[" + LARIOS + "]";
        var text = "Calle Larios " + unique();
        for (int i = 0; i < 10; i++) {
            assertThat(search(citizen, text).andReturn().getResponse().getStatus())
                    .as("search %d", i + 1)
                    .isEqualTo(200);
        }

        var response = search(citizen, text).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        // The ten searches were the same text: Nominatim was asked once.
        assertThat(NOMINATIM.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps the limit of one citizen from another citizen")
    void theLimitIsPerCitizen() throws Exception {
        NOMINATIM.body = "[]";
        var text = "Calle Larios " + unique();
        for (int i = 0; i < 11; i++) {
            search(citizen, text);
        }

        var other = search(user(Role.CITIZEN), text).andReturn().getResponse();

        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Nested
    @DisplayName("with the address enrichment switched off (ADDRESS_ENRICHMENT_ENABLED=false)")
    @TestPropertySource(properties = "urbanpulse.address.enabled=false")
    class WithTheAddressEnrichmentSwitchedOff {

        @Test
        @DisplayName("answers 503 GEOCODING_UNAVAILABLE without calling Nominatim")
        void answers503WithoutCallingNominatim() throws Exception {
            NOMINATIM.body = "[" + LARIOS + "]";

            expectUnavailable(search(citizen, "Calle Larios " + unique()));
            assertThat(NOMINATIM.calls.get()).isZero();
        }
    }

    @Nested
    @DisplayName("while Nominatim is busy with other calls")
    class WhileNominatimIsBusy {

        // The turn to call Nominatim is further away than a search waits.
        private static final Duration UNTIL_A_FREE_TURN = Duration.ofMillis(1500);

        @MockitoSpyBean private PlaceProvider provider;

        @Test
        @DisplayName(
                "answers 429 with Retry-After, and the refusal does not count against the user")
        void answers429ThatDoesNotCountAgainstTheUser() throws Exception {
            doThrow(new AddressSourceBusyException(UNTIL_A_FREE_TURN))
                    .when(provider)
                    .search(anyString(), anyInt());
            for (int i = 0; i < 11; i++) {
                var response =
                        search(citizen, "Calle Larios " + unique()).andReturn().getResponse();

                assertThat(response.getStatus()).as("search %d", i + 1).isEqualTo(429);
                assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                        .isEqualTo("TOO_MANY_ATTEMPTS");
                // Rounded up to whole seconds.
                assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
            }
            assertThat(NOMINATIM.calls.get()).isZero();

            reset(provider);
            NOMINATIM.body = "[" + LARIOS + "]";
            var free = search(citizen, "Calle Larios " + unique()).andReturn().getResponse();

            assertThat(free.getStatus()).isEqualTo(200);
        }
    }

    private void expectMalformed(MockHttpServletRequestBuilder request) throws Exception {
        var response =
                mvc.perform(
                                request.header(
                                        HttpHeaders.AUTHORIZATION,
                                        tokens.bearer(citizen, Role.CITIZEN)))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .isEqualTo("MALFORMED_REQUEST");
    }

    private static void expectUnavailable(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                .isEqualTo("GEOCODING_UNAVAILABLE");
    }

    private ResultActions search(UUID caller, String q) throws Exception {
        return mvc.perform(
                get(PLACES)
                        .param("q", q)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(caller, Role.CITIZEN)));
    }

    // Letters only, and different in every test: the cache outlives the test.
    private static String unique() {
        return UUID.randomUUID().toString().replaceAll("[^a-f]", "");
    }

    private UUID user(Role role) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, role)"
                        + " VALUES (?, ?, '{bcrypt}not-used', ?) RETURNING id",
                UUID.class,
                "Test " + role,
                role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                role.name());
    }

    /** A fake Nominatim with the JDK {@code HttpServer}: one answer for every call. */
    private static final class FakeNominatim implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService threads = Executors.newCachedThreadPool();
        final AtomicInteger calls = new AtomicInteger();
        volatile int status = 200;
        volatile String body = "[]";
        volatile Duration delay = Duration.ZERO;
        volatile String lastQuery = "";

        FakeNominatim() {
            try {
                server =
                        HttpServer.create(
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("The fake Nominatim could not start", e);
            }
            server.createContext("/search", this::answer);
            server.setExecutor(threads);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            calls.set(0);
            status = 200;
            body = "[]";
            delay = Duration.ZERO;
            lastQuery = "";
        }

        @Override
        public void close() {
            server.stop(0);
            threads.shutdownNow();
        }

        private void answer(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            lastQuery = exchange.getRequestURI().getRawQuery();
            try {
                Thread.sleep(delay.toMillis());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
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
