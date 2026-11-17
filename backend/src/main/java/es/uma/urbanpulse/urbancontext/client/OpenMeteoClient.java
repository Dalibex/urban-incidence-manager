package es.uma.urbanpulse.urbancontext.client;

import es.uma.urbanpulse.urbancontext.service.WeatherAnswerRejectedException;
import es.uma.urbanpulse.urbancontext.service.WeatherEnrichmentProperties;
import es.uma.urbanpulse.urbancontext.service.WeatherProvider;
import es.uma.urbanpulse.urbancontext.service.WeatherReading;
import es.uma.urbanpulse.urbancontext.service.WeatherSourceUnavailableException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
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

    static final String SOURCE = "Open-Meteo";

    private static final String CURRENT = "weather_code,wind_speed_10m,precipitation";
    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient http;
    private final JsonMapper json;

    OpenMeteoClient(WeatherEnrichmentProperties properties, JsonMapper json) {
        // The JDK client never follows a redirect by default, so a 3xx is an answer it cannot read.
        var client = HttpClient.newBuilder().connectTimeout(properties.openMeteoTimeout()).build();
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
        return SOURCE;
    }

    @Override
    public WeatherReading currentWeather(double latitude, double longitude) {
        String body;
        try {
            body =
                    http.get()
                            .uri(
                                    uri ->
                                            uri.path("/v1/forecast")
                                                    .queryParam("latitude", plain(latitude))
                                                    .queryParam("longitude", plain(longitude))
                                                    .queryParam("current", CURRENT)
                                                    .queryParam("timezone", "GMT")
                                                    .build())
                            .retrieve()
                            // The first handler that matches wins, so 429 is taken before the 4xx.
                            .onStatus(
                                    status ->
                                            status.value() == TOO_MANY_REQUESTS
                                                    || status.is5xxServerError(),
                                    (request, response) -> {
                                        throw new WeatherSourceUnavailableException(
                                                "Open-Meteo answered "
                                                        + response.getStatusCode().value());
                                    })
                            .onStatus(
                                    HttpStatusCode::isError,
                                    (request, response) -> {
                                        throw new WeatherAnswerRejectedException(
                                                "Open-Meteo answered "
                                                        + response.getStatusCode().value());
                                    })
                            .body(String.class);
        } catch (ResourceAccessException e) {
            throw unreachable("Open-Meteo could not be reached", e);
        } catch (RestClientException e) {
            // The headers arrived but the body did not, for example past the read timeout.
            throw unreachable("Open-Meteo stopped answering", e);
        }
        return read(body);
    }

    // The message names the cause (a timeout, a refused connection) but never the URL, which holds
    // the position of the report.
    private static WeatherSourceUnavailableException unreachable(String what, Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new WeatherSourceUnavailableException(
                what + " (" + cause.getClass().getSimpleName() + ")", e);
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
        return new WeatherReading(
                instant(current.path("time")),
                current.path("weather_code").isIntegralNumber()
                        ? current.path("weather_code").asInt()
                        : null,
                number(current.path("wind_speed_10m")),
                number(current.path("precipitation")));
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

    private static Double number(JsonNode value) {
        return value.isNumber() ? value.asDouble() : null;
    }
}
