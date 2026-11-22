package es.uma.urbanpulse.geospatial.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.uma.urbanpulse.geospatial.service.AddressAnswerRejectedException;
import es.uma.urbanpulse.geospatial.service.AddressEnrichmentProperties;
import es.uma.urbanpulse.geospatial.service.AddressLookup;
import es.uma.urbanpulse.geospatial.service.AddressSourceUnavailableException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The Nominatim client against a fake server of the JDK: no real network. */
class NominatimClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final String USER_AGENT = "UrbanPulse tests";

    // Calle Larios, Málaga: latitude and longitude are far apart, so a swap shows.
    private static final double LATITUDE = 36.7213;
    private static final double LONGITUDE = -4.4214;

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile long delayMillis;
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private NominatimClient client;

    @BeforeEach
    void startFakeNominatim() throws IOException {
        status = 200;
        body = "";
        delayMillis = 0;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        client = clientFor(server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeNominatim() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        query.set(exchange.getRequestURI().getRawQuery());
        userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // The client gave up first (a timeout).
        } finally {
            exchange.close();
        }
    }

    private static NominatimClient clientFor(int port) {
        var properties =
                new AddressEnrichmentProperties(
                        true,
                        URI.create("http://127.0.0.1:" + port),
                        null,
                        USER_AGENT,
                        Duration.ofSeconds(30),
                        10,
                        Duration.ZERO,
                        TIMEOUT,
                        Duration.ofHours(24));
        return new NominatimClient(properties, JsonMapper.builder().build());
    }

    private void answerWith(String address) {
        body = "{\"place_id\": 1, \"address\": {" + address + "}}";
    }

    private static final String LARIOS =
            "\"house_number\": \"5\", \"road\": \"Calle Larios\", \"suburb\": \"Centro\","
                    + " \"city\": \"Málaga\", \"postcode\": \"29005\", \"country_code\": \"es\"";

    @Test
    void aStreetInMalagaIsTheNormalizedAddress() {
        answerWith(LARIOS);

        assertThat(client.lookUp(LATITUDE, LONGITUDE))
                .isEqualTo(new AddressLookup.Found("Calle Larios, 5, 29005 Málaga"));
    }

    @Test
    void theRequestAsksForTheReverseAddressAndIdentifiesTheApplication() {
        answerWith(LARIOS);

        client.lookUp(LATITUDE, LONGITUDE);

        assertThat(path.get()).isEqualTo("/reverse");
        assertThat(query.get())
                .contains("format=jsonv2")
                .contains("lat=36.7213")
                .contains("lon=-4.4214")
                .contains("addressdetails=1")
                .contains("accept-language=es");
        assertThat(userAgent.get()).isEqualTo(USER_AGENT);
    }

    @Test
    void aSmallCoordinateIsSentWithoutAnExponent() {
        answerWith(LARIOS);

        client.lookUp(0.0001, -0.0001);

        assertThat(query.get()).contains("lat=0.0001").contains("lon=-0.0001");
    }

    @Test
    void anAddressWithoutNumberOrPostcodeKeepsTheStreetAndTheCity() {
        answerWith(
                "\"pedestrian\": \"Plaza de la Constitución\", \"city\": \"Málaga\","
                        + " \"country_code\": \"es\"");

        assertThat(client.lookUp(LATITUDE, LONGITUDE))
                .isEqualTo(new AddressLookup.Found("Plaza de la Constitución, Málaga"));
    }

    @Test
    void theMunicipalityIsComparedWithoutAccentsOrCase() {
        answerWith("\"road\": \"Calle Larios\", \"city\": \"MALAGA\", \"country_code\": \"es\"");

        assertThat(client.lookUp(LATITUDE, LONGITUDE))
                .isEqualTo(new AddressLookup.Found("Calle Larios, Málaga"));
    }

    @Test
    void anotherMunicipalityIsOutsideTheArea() {
        answerWith(
                "\"road\": \"Calle San Miguel\", \"town\": \"Torremolinos\","
                        + " \"country_code\": \"es\"");

        assertThat(client.lookUp(36.62, -4.50)).isEqualTo(new AddressLookup.OutsideArea());
    }

    @Test
    void anotherCountryIsOutsideTheArea() {
        answerWith("\"road\": \"Calle Larios\", \"city\": \"Málaga\", \"country_code\": \"co\"");

        assertThat(client.lookUp(LATITUDE, LONGITUDE)).isEqualTo(new AddressLookup.OutsideArea());
    }

    @Test
    void aPlaceNominatimCannotGeocodeIsOutsideTheArea() {
        body = "{\"error\": \"Unable to geocode\"}";

        assertThat(client.lookUp(36.60, -4.40)).isEqualTo(new AddressLookup.OutsideArea());
    }

    @Test
    void aPlaceInMalagaWithoutAStreetHasNoStreet() {
        answerWith(
                "\"leisure\": \"Parque del Oeste\", \"city\": \"Málaga\", \"road\": \" \","
                        + " \"country_code\": \"es\"");

        assertThat(client.lookUp(LATITUDE, LONGITUDE)).isEqualTo(new AddressLookup.NoStreet());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void aBusyOrFailingSourceIsUnavailable(int code) {
        status = code;
        body = "{}";

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressSourceUnavailableException.class)
                .hasMessageContaining(String.valueOf(code));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void aRefusedRequestIsRejected(int code) {
        status = code;
        body = "{}";

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aSlowAnswerIsUnavailableWithoutThePositionInTheMessage() {
        delayMillis = TIMEOUT.toMillis() * 3;
        answerWith(LARIOS);

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressSourceUnavailableException.class)
                .message()
                .doesNotContain("36.7213")
                .doesNotContain("-4.4214");
    }

    @Test
    void aClosedPortIsUnavailable() throws IOException {
        int closed;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }

        assertThatThrownBy(() -> clientFor(closed).lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressSourceUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[1, 2]", "{\"place_id\": 1}"})
    void anAnswerItCannotReadIsRejected(String answer) {
        body = answer;

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aStreetThatIsNotATextIsRejected() {
        answerWith("\"road\": 12, \"city\": \"Málaga\", \"country_code\": \"es\"");

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aStreetWithAControlCharacterIsRejected() {
        answerWith(
                "\"road\": \"Calle\\u0000Larios\", \"city\": \"Málaga\", \"country_code\": \"es\"");

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void anAnswerOverTheLimitIsRejected() {
        answerWith(LARIOS + ", \"padding\": \"" + "x".repeat(70 * 1024) + "\"");

        assertThatThrownBy(() -> client.lookUp(LATITUDE, LONGITUDE))
                .isInstanceOf(AddressAnswerRejectedException.class)
                .hasMessageContaining("limit");
    }
}
