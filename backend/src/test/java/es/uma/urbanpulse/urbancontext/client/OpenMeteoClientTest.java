package es.uma.urbanpulse.urbancontext.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.uma.urbanpulse.urbancontext.service.WeatherAnswerRejectedException;
import es.uma.urbanpulse.urbancontext.service.WeatherEnrichmentProperties;
import es.uma.urbanpulse.urbancontext.service.WeatherSourceUnavailableException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The Open-Meteo client against a fake server of the JDK: no real network. */
class OpenMeteoClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile long delayMillis;
    private volatile boolean stallAfterHeaders;
    private volatile boolean dripBody;
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private OpenMeteoClient client;

    @BeforeEach
    void startFakeOpenMeteo() throws IOException {
        status = 200;
        body = "";
        delayMillis = 0;
        stallAfterHeaders = false;
        dripBody = false;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        client = clientFor(server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeOpenMeteo() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        query.set(exchange.getRequestURI().getRawQuery());
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            if (dripBody) {
                // Chunked, a byte every 50 ms for far longer than the timeout.
                exchange.sendResponseHeaders(status, 0);
                for (int i = 0; i < TIMEOUT.toMillis() * 8 / 50; i++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    Thread.sleep(50);
                }
                return;
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (stallAfterHeaders) {
                // Half the body, then nothing: the headers arrived, the rest never does in time.
                exchange.getResponseBody().write(bytes, 0, bytes.length / 2);
                exchange.getResponseBody().flush();
                Thread.sleep(TIMEOUT.toMillis() * 4);
            } else if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException e) {
            // The client gave up (the timeout test): nothing left to answer.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static OpenMeteoClient clientFor(int port) {
        var properties =
                new WeatherEnrichmentProperties(
                        true,
                        URI.create("http://127.0.0.1:" + port),
                        null,
                        Duration.ofSeconds(10),
                        10,
                        TIMEOUT,
                        Duration.ofMinutes(10));
        return new OpenMeteoClient(properties, JsonMapper.builder().build());
    }

    @Test
    void readsTheCurrentValuesAndAsksForThem() {
        body =
                """
                {"current":{"time":"2026-11-17T20:45","interval":900,
                 "weather_code":61,"wind_speed_10m":12.5,"precipitation":0.4}}
                """;

        var reading = client.currentWeather(36.7213, -4.4214);

        assertThat(reading.observedAt()).isEqualTo(Instant.parse("2026-11-17T20:45:00Z"));
        assertThat(reading.weatherCode()).isEqualTo(61);
        assertThat(reading.windSpeedKmh()).isEqualTo(12.5);
        assertThat(reading.precipitationMm()).isEqualTo(0.4);
        assertThat(path.get()).isEqualTo("/v1/forecast");
        assertThat(query.get())
                .contains("latitude=36.7213")
                .contains("longitude=-4.4214")
                .contains("timezone=GMT")
                .contains("weather_code")
                .contains("wind_speed_10m")
                .contains("precipitation");
        assertThat(client.source()).isEqualTo("Open-Meteo");
    }

    @Test
    void aCurrentWithoutAWeatherCodeIsAReadingWithNullCode() {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"wind_speed_10m\":3.0}}";

        var reading = client.currentWeather(36.7, -4.4);

        assertThat(reading.weatherCode()).isNull();
        assertThat(reading.windSpeedKmh()).isEqualTo(3.0);
        assertThat(reading.precipitationMm()).isNull();
    }

    @Test
    void aCurrentWithoutATimeHasNoObservationInstant() {
        body = "{\"current\":{\"weather_code\":0}}";

        var reading = client.currentWeather(36.7, -4.4);

        assertThat(reading.observedAt()).isNull();
        assertThat(reading.weatherCode()).isZero();
    }

    @Test
    void tooManyRequestsIsUnavailable() {
        status = 429;
        body = "{\"reason\":\"limit\"}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherSourceUnavailableException.class)
                .hasMessageContaining("429");
    }

    @Test
    void aServerErrorIsUnavailable() {
        status = 503;
        body = "{}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherSourceUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void aClientErrorIsRejected() {
        status = 400;
        body = "{\"error\":true,\"reason\":\"Latitude must be in range\"}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class)
                .hasMessageContaining("400");
    }

    @Test
    void aNotFoundIsRejected() {
        status = 404;
        body = "{\"error\":true}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class)
                .hasMessageContaining("404");
    }

    @Test
    void aBodyOverTheLimitIsRejected() {
        body = bodyOfBytes(MAX_BODY_BYTES + 1);

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void aBodyAtTheLimitIsRead() {
        body = bodyOfBytes(MAX_BODY_BYTES);

        var reading = client.currentWeather(36.7, -4.4);

        assertThat(reading.weatherCode()).isZero();
    }

    // A valid answer padded with a field the client ignores, of exactly the given size.
    private static String bodyOfBytes(int size) {
        var start = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":0},\"pad\":\"";
        var end = "\"}";
        return start + "x".repeat(size - start.length() - end.length()) + end;
    }

    @Test
    void anEmptyBodyIsRejected() {
        status = 200;
        body = "";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class);
    }

    @Test
    void jsonThatCannotBeReadIsRejected() {
        body = "<html>not json";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class);
    }

    @Test
    void anAnswerWithoutCurrentIsRejected() {
        body = "{\"latitude\":36.7}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class)
                .hasMessageContaining("current");
    }

    @Test
    void aTimeThatCannotBeReadIsRejected() {
        body = "{\"current\":{\"time\":\"yesterday\",\"weather_code\":0}}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class);
    }

    @Test
    void anAnswerSlowerThanTheTimeoutIsUnavailable() {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":0}}";
        delayMillis = TIMEOUT.toMillis() * 4;

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherSourceUnavailableException.class);
    }

    @Test
    void aBodyThatStopsAfterTheHeadersIsUnavailable() {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":0}}";
        stallAfterHeaders = true;

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherSourceUnavailableException.class);
    }

    @Test
    void aBodyThatKeepsDrippingPastTheTimeoutIsUnavailable() {
        dripBody = true;

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherSourceUnavailableException.class);
    }

    @Test
    void aWeatherCodeTooBigForAnIntIsRejected() {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":3000000000}}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"wind_speed_10m\":1e400",
                "\"wind_speed_10m\":-3.0",
                "\"precipitation\":1e400",
                "\"precipitation\":-0.1"
            })
    void aMeasureThatIsNotFiniteOrIsNegativeIsRejected(String measure) {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\"," + measure + "}}";

        assertThatThrownBy(() -> client.currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherAnswerRejectedException.class);
    }

    @Test
    void anUnreachableSourceNeverLeaksThePositionInTheMessageOrTheCauses() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        Throwable thrown =
                catchThrowable(() -> clientFor(closedPort).currentWeather(36.7213, -4.4214));

        assertThat(thrown).isInstanceOf(WeatherSourceUnavailableException.class);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage()))
                    .doesNotContain("36.7213")
                    .doesNotContain("-4.4214");
        }
    }

    @Test
    void aPositionNearZeroIsSentInPlainDecimals() {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":0}}";

        client.currentWeather(0.00001, -0.0001);

        assertThat(query.get()).contains("latitude=0.00001").contains("longitude=-0.0001");
    }

    @Test
    void withAProxyTheCallGoesToTheProxyAndNotToOpenMeteo() throws IOException {
        var requestLine = new AtomicReference<String>();
        var proxy =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        proxy.createContext(
                "/",
                exchange -> {
                    // For plain http the JDK sends the absolute form of the target.
                    requestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    var bytes =
                            "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":3}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        proxy.start();
        try {
            var reading =
                    clientThrough(
                                    "http://localhost:" + server.getAddress().getPort(),
                                    URI.create("http://localhost:" + proxy.getAddress().getPort()))
                            .currentWeather(36.7, -4.4);

            assertThat(reading.weatherCode()).isEqualTo(3);
            assertThat(requestLine.get())
                    .startsWith(
                            "GET http://localhost:"
                                    + server.getAddress().getPort()
                                    + "/v1/forecast?");
            assertThat(path.get()).isNull();
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void withAProxyAnHttpsCallIsTunnelledAndNeverFallsBackToADirectConnection() throws Exception {
        var firstLine = new AtomicReference<String>();
        var targetHit = new AtomicBoolean();
        try (var target = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
                var proxy = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            var targetAcceptor =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var ignored = target.accept()) {
                                            targetHit.set(true);
                                        } catch (IOException e) {
                                            // Closed at the end of the test: nobody connected.
                                        }
                                    });
            var proxyThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var connection = proxy.accept()) {
                                            var in =
                                                    new BufferedReader(
                                                            new InputStreamReader(
                                                                    connection.getInputStream(),
                                                                    StandardCharsets.US_ASCII));
                                            firstLine.set(in.readLine());
                                            connection
                                                    .getOutputStream()
                                                    .write(
                                                            "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .US_ASCII));
                                        } catch (IOException e) {
                                            // The client gave up: the first line is what counts.
                                        }
                                    });

            var thrown =
                    catchThrowable(
                            () ->
                                    clientThrough(
                                                    "https://localhost:" + target.getLocalPort(),
                                                    URI.create(
                                                            "http://localhost:"
                                                                    + proxy.getLocalPort()))
                                            .currentWeather(36.7, -4.4));
            proxyThread.join(TIMEOUT.toMillis() * 4);

            assertThat(firstLine.get())
                    .isEqualTo("CONNECT localhost:" + target.getLocalPort() + " HTTP/1.1");
            assertThat(targetHit).isFalse();
            // The JDK fails the tunnel with an IOException, which RestClient wraps in a
            // ResourceAccessException: the source is unreachable, not an answer to reject.
            assertThat(thrown).isInstanceOf(WeatherSourceUnavailableException.class);
            target.close();
            targetAcceptor.join(TIMEOUT.toMillis() * 4);
            assertThat(targetHit).isFalse();
        }
    }

    @Test
    void withoutAProxyTheCallGoesStraightToOpenMeteo() {
        body = "{\"current\":{\"time\":\"2026-11-17T20:45\",\"weather_code\":3}}";

        client.currentWeather(36.7, -4.4);

        assertThat(path.get()).isEqualTo("/v1/forecast");
    }

    private static OpenMeteoClient clientThrough(String baseUrl, URI proxy) {
        var properties =
                new WeatherEnrichmentProperties(
                        true,
                        URI.create(baseUrl),
                        proxy,
                        Duration.ofSeconds(10),
                        10,
                        TIMEOUT,
                        Duration.ofMinutes(10));
        return new OpenMeteoClient(properties, JsonMapper.builder().build());
    }

    @Test
    void aClosedPortIsUnavailable() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> clientFor(closedPort).currentWeather(36.7, -4.4))
                .isInstanceOf(WeatherSourceUnavailableException.class);
    }
}
