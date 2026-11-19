package es.uma.urbanpulse.urbancontext.client;

import es.uma.urbanpulse.urbancontext.dto.ContextSource;
import es.uma.urbanpulse.urbancontext.service.WeatherAnswerRejectedException;
import es.uma.urbanpulse.urbancontext.service.WeatherEnrichmentProperties;
import es.uma.urbanpulse.urbancontext.service.WeatherProvider;
import es.uma.urbanpulse.urbancontext.service.WeatherReading;
import es.uma.urbanpulse.urbancontext.service.WeatherSourceUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The current weather from Open-Meteo's forecast endpoint (ADR-0018): no key, coordinates in, the
 * WMO code, the wind and the precipitation out, in UTC.
 */
@Component
class OpenMeteoClient implements WeatherProvider {

    private static final String CURRENT = "weather_code,wind_speed_10m,precipitation";
    private static final int TOO_MANY_REQUESTS = 429;
    // A real answer is well under 1 KiB; the limit keeps a hostile or misrouted answer from
    // filling the memory of the backend.
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final RestClient http;
    private final JsonMapper json;

    OpenMeteoClient(WeatherEnrichmentProperties properties, JsonMapper json) {
        // The JDK client never follows a redirect by default, so a 3xx is an answer it cannot read.
        var builder = HttpClient.newBuilder().connectTimeout(properties.openMeteoTimeout());
        var proxy = properties.openMeteoProxy();
        if (proxy != null) {
            // The deployment has no other way out (ADR-0011): the proxy is the only service with
            // internet. Unresolved, so the name is resolved by the JDK when it connects.
            builder.proxy(
                    ProxySelector.of(
                            InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
        }
        var client = builder.build();
        var requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(properties.openMeteoTimeout());
        this.http =
                RestClient.builder()
                        .baseUrl(properties.openMeteoBaseUrl().toString())
                        .requestFactory(requests)
                        .build();
        this.json = json;
    }

    @Override
    public String source() {
        return ContextSource.OPEN_METEO.name();
    }

    @Override
    public WeatherReading currentWeather(double latitude, double longitude) {
        try {
            return http.get()
                    .uri(
                            uri ->
                                    uri.path("/v1/forecast")
                                            .queryParam("latitude", plain(latitude))
                                            .queryParam("longitude", plain(longitude))
                                            .queryParam("current", CURRENT)
                                            .queryParam("timezone", "GMT")
                                            .build())
                    .exchange(
                            (request, response) -> {
                                rejectErrors(response.getStatusCode());
                                return read(boundedBody(response.getBody()));
                            });
        } catch (ResourceAccessException e) {
            throw unreachable("Open-Meteo could not be reached", e);
        } catch (RestClientException e) {
            // The headers arrived but the body did not, for example past the read timeout.
            throw unreachable("Open-Meteo stopped answering", e);
        }
    }

    private static void rejectErrors(HttpStatusCode status) {
        // 429 is taken before the other 4xx.
        if (status.value() == TOO_MANY_REQUESTS || status.is5xxServerError()) {
            throw new WeatherSourceUnavailableException("Open-Meteo answered " + status.value());
        }
        if (status.isError()) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered " + status.value());
        }
    }

    // Content-Length can be missing (chunked) or false, so the limit is on the bytes actually read.
    private static String boundedBody(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered more than the limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // The message names the type of the cause (a timeout, a refused connection) but never the URL,
    // which holds the position of the report: the cause itself is dropped because its message
    // carries that URL.
    private static WeatherSourceUnavailableException unreachable(String what, Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new WeatherSourceUnavailableException(
                what + " (" + cause.getClass().getSimpleName() + ")");
    }

    // Double.toString writes 1.0E-4 for a value near 0, which Open-Meteo does not read.
    private static String plain(double degrees) {
        return BigDecimal.valueOf(degrees).toPlainString();
    }

    private WeatherReading read(String body) {
        if (body == null || body.isBlank()) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered without a body");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered JSON it cannot read", e);
        }
        var current = root.path("current");
        if (!current.isObject()) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered without current values");
        }
        try {
            var code = current.path("weather_code");
            return new WeatherReading(
                    instant(current.path("time")),
                    weatherCode(code),
                    measure(current.path("wind_speed_10m")),
                    measure(current.path("precipitation")));
        } catch (JacksonException e) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered values it cannot map", e);
        }
    }

    // With timezone=GMT, Open-Meteo writes the time in UTC without its offset (2026-11-17T20:45).
    private static Instant instant(JsonNode time) {
        if (!time.isString()) {
            return null;
        }
        try {
            return LocalDateTime.parse(time.asString()).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new WeatherAnswerRejectedException(
                    "Open-Meteo answered a time it cannot read", e);
        }
    }

    // A code outside the range of an int cannot be a WMO code, and asInt() would throw on it.
    private static Integer weatherCode(JsonNode code) {
        if (!code.isIntegralNumber()) {
            return null;
        }
        if (!code.canConvertToInt()) {
            throw new WeatherAnswerRejectedException("Open-Meteo answered a weather code too big");
        }
        return code.asInt();
    }

    // Wind and precipitation cannot be negative, and 1e400 would read as Infinity: such an answer
    // is not a measurement. A missing value stays null, because unknown is not zero.
    private static Double measure(JsonNode value) {
        if (!value.isNumber()) {
            return null;
        }
        double measure = value.asDouble();
        if (!Double.isFinite(measure) || measure < 0) {
            throw new WeatherAnswerRejectedException(
                    "Open-Meteo answered a measure that is not a finite, non-negative number");
        }
        return measure;
    }
}
