package es.uma.urbanpulse.urbancontext.service;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The weather enrichment of ADR-0018, from the environment (docs/deployment.md).
 *
 * @param enabled whether the worker runs ({@code WEATHER_ENRICHMENT_ENABLED}). Off, reports still
 *     create their {@code PENDING} context
 * @param openMeteoBaseUrl where Open-Meteo answers ({@code OPEN_METEO_BASE_URL}), so the tests
 *     point to a fake server
 * @param openMeteoProxy HTTP forward proxy the calls go through ({@code OPEN_METEO_PROXY}), for a
 *     deployment with no other way out (ADR-0011); null, or empty in the environment, calls
 *     Open-Meteo directly
 * @param interval time between two runs of the worker ({@code WEATHER_ENRICHMENT_INTERVAL})
 * @param batchSize contexts per run ({@code WEATHER_ENRICHMENT_BATCH_SIZE}), which also caps the
 *     rate of calls to Open-Meteo
 * @param openMeteoTimeout connection and read timeout of each call ({@code OPEN_METEO_TIMEOUT})
 * @param maxDelay from this age of the report on, the current weather does not stand for the
 *     weather at the report ({@code WEATHER_MAX_DELAY})
 */
@ConfigurationProperties("urbanpulse.weather")
public record WeatherEnrichmentProperties(
        boolean enabled,
        URI openMeteoBaseUrl,
        URI openMeteoProxy,
        Duration interval,
        int batchSize,
        Duration openMeteoTimeout,
        Duration maxDelay) {

    private static final String BASE_URL = "urbanpulse.weather.open-meteo-base-url";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private static final String PROXY = "urbanpulse.weather.open-meteo-proxy";

    public WeatherEnrichmentProperties {
        requireSafeBaseUrl(openMeteoBaseUrl);
        // An unset OPEN_METEO_PROXY reaches the binder as an empty string.
        if (openMeteoProxy != null && openMeteoProxy.toString().isBlank()) {
            openMeteoProxy = null;
        }
        if (openMeteoProxy != null) {
            requireProxy(openMeteoProxy);
        }
        requirePositive(interval, "urbanpulse.weather.interval");
        requirePositive(openMeteoTimeout, "urbanpulse.weather.open-meteo-timeout");
        requirePositive(maxDelay, "urbanpulse.weather.max-delay");
        if (batchSize < 1) {
            throw new IllegalArgumentException("urbanpulse.weather.batch-size must be at least 1");
        }
    }

    // The worker sends the position of reports to this address, so a mistaken value must not send
    // it in clear text to a remote host, to a place the URL smuggles in (user info, query) or to
    // anything but HTTP. Plain http is only for a fake server on this machine.
    private static void requireSafeBaseUrl(URI url) {
        if (url == null) {
            throw new IllegalArgumentException(BASE_URL + " is required");
        }
        if (url.getHost() == null) {
            throw new IllegalArgumentException(BASE_URL + " must have a host");
        }
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        boolean loopbackHttp =
                "http".equalsIgnoreCase(url.getScheme())
                        && LOOPBACK_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT));
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException(
                    BASE_URL + " must be https, or http only for localhost");
        }
        if (url.getRawUserInfo() != null
                || url.getRawQuery() != null
                || url.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    BASE_URL + " must not have user info, a query or a fragment");
        }
    }

    // The proxy only tunnels the HTTPS calls with CONNECT, so TLS stays end to end with Open-Meteo
    // and the proxy never sees the position of a report.
    private static void requireProxy(URI url) {
        if (!"http".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException(PROXY + " must be http");
        }
        if (url.getHost() == null || url.getPort() < 0) {
            throw new IllegalArgumentException(PROXY + " must have a host and a port");
        }
        boolean emptyPath =
                url.getRawPath() == null
                        || url.getRawPath().isEmpty()
                        || "/".equals(url.getRawPath());
        if (url.getRawUserInfo() != null
                || !emptyPath
                || url.getRawQuery() != null
                || url.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    PROXY + " must not have user info, a path, a query or a fragment");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
