package es.uma.urbanpulse.geospatial.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.uma.urbanpulse.geospatial.service.AddressAnswerRejectedException;
import es.uma.urbanpulse.geospatial.service.AddressEnrichmentProperties;
import es.uma.urbanpulse.geospatial.service.AddressLookup;
import es.uma.urbanpulse.geospatial.service.AddressSourceBusyException;
import es.uma.urbanpulse.geospatial.service.AddressSourceUnavailableException;
import es.uma.urbanpulse.geospatial.service.Place;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.UriComponentsBuilder;
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
    private final AtomicInteger requests = new AtomicInteger();
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
        requests.incrementAndGet();
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
        return clientFor(port, Duration.ZERO);
    }

    private static NominatimClient clientFor(int port, Duration spacing) {
        var properties =
                new AddressEnrichmentProperties(
                        true,
                        URI.create("http://127.0.0.1:" + port),
                        null,
                        USER_AGENT,
                        Duration.ofSeconds(30),
                        10,
                        spacing,
                        TIMEOUT,
                        Duration.ofHours(24));
        return new NominatimClient(properties, JsonMapper.builder().build(), Clock.systemUTC());
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

    // The address search of #179.

    private static String place(
            String displayName,
            String lat,
            String lon,
            String municipalityKey,
            String municipality,
            String country) {
        return "{\"display_name\": \""
                + displayName
                + "\", \"lat\": \""
                + lat
                + "\", \"lon\": \""
                + lon
                + "\", \"address\": {\""
                + municipalityKey
                + "\": \""
                + municipality
                + "\", \"country_code\": \""
                + country
                + "\"}}";
    }

    private static String places(String... places) {
        return "[" + String.join(",", places) + "]";
    }

    private static String malaga(String name) {
        return place(name, "36.72", "-4.42", "city", "Málaga", "es");
    }

    private static final String LARIOS_NAME = "Calle Larios, Centro, Málaga, Andalucía, España";

    @Test
    void aSearchIsAskedBoundedToMalagaAndIdentifiesTheApplication() {
        body = "[]";

        client.search("Calle Larios 5", 5);

        assertThat(path.get()).isEqualTo("/search");
        assertThat(query.get())
                .contains("format=jsonv2")
                .contains("countrycodes=es")
                .contains("viewbox=" + NominatimClient.MALAGA_VIEWBOX)
                .contains("bounded=1")
                .contains("addressdetails=1")
                .contains("accept-language=es")
                // Twice the limit, because places outside the municipality are dropped.
                .contains("limit=10");
        assertThat(userAgent.get()).isEqualTo(USER_AGENT);
    }

    @Test
    void theTextArrivesIntactWithReservedCharactersSpacesAndAccents() {
        body = "[]";
        var text = "Calle Álamos & Plaza #5 100%+ñ";

        client.search(text, 5);

        // Decoding the raw query shows what Nominatim reads: q is one parameter with the whole
        // text.
        var parameters =
                UriComponentsBuilder.fromUriString("http://x/search?" + query.get())
                        .build()
                        .getQueryParams();
        assertThat(parameters.get("q")).hasSize(1);
        assertThat(URLDecoder.decode(parameters.getFirst("q"), StandardCharsets.UTF_8))
                .isEqualTo(text);
        assertThat(parameters.get("limit")).containsExactly("10");
        assertThat(parameters.keySet())
                .containsExactlyInAnyOrder(
                        "format",
                        "q",
                        "countrycodes",
                        "viewbox",
                        "bounded",
                        "addressdetails",
                        "limit",
                        "accept-language");
    }

    @Test
    void aPlaceOfMalagaIsReturnedWithItsPosition() {
        body = places(place(LARIOS_NAME, "36.7213", "-4.4214", "city", "Málaga", "es"));

        assertThat(client.search("Larios", 5))
                .containsExactly(new Place(LARIOS_NAME, LATITUDE, LONGITUDE));
    }

    @Test
    void thePlacesKeepTheOrderOfNominatim() {
        body = places(malaga("Uno"), malaga("Dos"), malaga("Tres"));

        assertThat(client.search("calle", 5))
                .extracting(Place::displayName)
                .containsExactly("Uno", "Dos", "Tres");
    }

    @Test
    void atMostTheLimitIsReturned() {
        body = places(malaga("A"), malaga("B"), malaga("C"), malaga("D"));

        assertThat(client.search("calle", 2))
                .extracting(Place::displayName)
                .containsExactly("A", "B");
    }

    @Test
    void aPlaceOfAnotherMunicipalityIsDroppedAndTheNextOnesFillTheList() {
        body =
                places(
                        place("Torremolinos 1", "36.62", "-4.50", "town", "Torremolinos", "es"),
                        malaga("Malaga 1"),
                        place("Rincon 1", "36.71", "-4.27", "town", "Rincón de la Victoria", "es"),
                        malaga("Malaga 2"));

        assertThat(client.search("calle", 5))
                .extracting(Place::displayName)
                .containsExactly("Malaga 1", "Malaga 2");
    }

    @Test
    void aPlaceOfAnotherCountryIsDropped() {
        body = places(place("Málaga, Colombia", "6.9", "-73.4", "town", "Málaga", "co"));

        assertThat(client.search("malaga", 5)).isEmpty();
    }

    @Test
    void aPlaceWithoutAnAddressIsDropped() {
        body = "[{\"display_name\": \"Somewhere\", \"lat\": \"36.7\", \"lon\": \"-4.4\"}]";

        assertThat(client.search("somewhere", 5)).isEmpty();
    }

    @Test
    void theMunicipalityOfAPlaceIsComparedWithoutAccentsOrCase() {
        body = places(place("Calle Larios", "36.7", "-4.4", "municipality", "MALAGA", "es"));

        assertThat(client.search("larios", 5)).hasSize(1);
    }

    @Test
    void anEmptyListIsNoPlaces() {
        body = "[]";

        assertThat(client.search("nowhere", 5)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void aBusyOrFailingSourceMakesTheSearchUnavailable(int code) {
        status = code;
        body = "{}";

        assertThatThrownBy(() -> client.search("Larios", 5))
                .isInstanceOf(AddressSourceUnavailableException.class)
                .hasMessageContaining(String.valueOf(code));
    }

    @Test
    void aSlowAnswerMakesTheSearchUnavailableWithoutTheTextInTheMessage() {
        delayMillis = TIMEOUT.toMillis() * 3;
        body = places(malaga("Larios"));

        assertThatThrownBy(() -> client.search("Mi calle secreta", 5))
                .isInstanceOf(AddressSourceUnavailableException.class)
                .message()
                .doesNotContain("secreta");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not json",
                "{\"error\": \"x\"}",
                "[1]",
                "[\"text\"]",
                "[{\"display_name\": \"A\", \"lon\": \"-4.4\", \"address\": {\"city\": \"Málaga\","
                        + " \"country_code\": \"es\"}}]",
                "[{\"display_name\": \"A\", \"lat\": \"abc\", \"lon\": \"-4.4\", \"address\":"
                        + " {\"city\": \"Málaga\", \"country_code\": \"es\"}}]",
                "[{\"display_name\": \"A\", \"lat\": \"123\", \"lon\": \"-4.4\", \"address\":"
                        + " {\"city\": \"Málaga\", \"country_code\": \"es\"}}]",
                "[{\"display_name\": \"A\", \"lat\": \"36.7\", \"lon\": \"-181\", \"address\":"
                        + " {\"city\": \"Málaga\", \"country_code\": \"es\"}}]",
                "[{\"lat\": \"36.7\", \"lon\": \"-4.4\", \"address\": {\"city\": \"Málaga\","
                        + " \"country_code\": \"es\"}}]"
            })
    void anAnswerItCannotUseIsRejected(String answer) {
        body = answer;

        assertThatThrownBy(() -> client.search("Larios", 5))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aDisplayNameTooLongIsRejected() {
        body = places(malaga("x".repeat(501)));

        assertThatThrownBy(() -> client.search("Larios", 5))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aDisplayNameWithAControlCharacterIsRejected() {
        body = places(malaga("Calle\\u0007Larios"));

        assertThatThrownBy(() -> client.search("Larios", 5))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aRefusedSearchIsRejected() {
        status = 403;
        body = "{}";

        assertThatThrownBy(() -> client.search("Larios", 5))
                .isInstanceOf(AddressAnswerRejectedException.class);
    }

    @Test
    void aSearchBehindAQueueLongerThanOneSpacingIsBusyAndDoesNotCallNominatim() throws Exception {
        var spaced = clientFor(server.getAddress().getPort(), Duration.ofMinutes(1));
        body = "[]";
        spaced.search("Larios", 5);
        // A look-up of the worker takes the next turn and waits a minute for it.
        var worker =
                new Thread(
                        () -> {
                            try {
                                spaced.lookUp(LATITUDE, LONGITUDE);
                            } catch (RuntimeException e) {
                                // Interrupted at the end of the test.
                            }
                        });
        worker.start();
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        try {
            assertThatThrownBy(() -> spaced.search("Alameda", 5))
                    .isInstanceOfSatisfying(
                            AddressSourceBusyException.class,
                            e ->
                                    assertThat(e.retryAfter())
                                            .isPositive()
                                            .isLessThanOrEqualTo(Duration.ofMinutes(1)));
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            worker.interrupt();
            worker.join();
        }
    }

    @Test
    void aSearchRightAfterAnotherWaitsForItsTurnInsteadOfBeingRefused() {
        var spaced = clientFor(server.getAddress().getPort(), Duration.ofMillis(200));
        body = places(malaga("Larios"));
        spaced.search("Larios", 5);

        assertThat(spaced.search("Alameda", 5)).hasSize(1);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aResultThatCannotBeReadIsDroppedAndTheOthersAreKept() {
        body =
                "[{\"display_name\": \"A\", \"lat\": \"abc\", \"lon\": \"-4.4\", \"address\":"
                        + " {\"city\": \"Málaga\", \"country_code\": \"es\"}}, 7, "
                        + places(malaga("Larios")).substring(1);

        assertThat(client.search("Larios", 5)).hasSize(1);
    }

    @Test
    void aSearchTakesTheTurnSoTheNextLookUpWaitsInsteadOfFailing() {
        var spaced = clientFor(server.getAddress().getPort(), Duration.ofMillis(100));
        body = "[]";
        spaced.search("Larios", 5);
        answerWith(LARIOS);

        assertThat(spaced.lookUp(LATITUDE, LONGITUDE))
                .isEqualTo(new AddressLookup.Found("Calle Larios, 5, 29005 Málaga"));
        assertThat(requests.get()).isEqualTo(2);
    }
}
