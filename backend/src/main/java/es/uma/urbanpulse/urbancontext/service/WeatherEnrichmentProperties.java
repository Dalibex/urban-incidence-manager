package es.uma.urbanpulse.urbancontext.service;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The weather enrichment of ADR-0018, from the environment (docs/deployment.md).
 *
 * @param enabled whether the worker runs ({@code WEATHER_ENRICHMENT_ENABLED}). Off, reports still
 *     create their {@code PENDING} context
 * @param openMeteoBaseUrl where Open-Meteo answers ({@code OPEN_METEO_BASE_URL}), so the tests
 *     point to a fake server
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
        Duration interval,
        int batchSize,
        Duration openMeteoTimeout,
        Duration maxDelay) {

    public WeatherEnrichmentProperties {
        if (openMeteoBaseUrl == null) {
            throw new IllegalArgumentException(
                    "urbanpulse.weather.open-meteo-base-url is required");
        }
        requirePositive(interval, "urbanpulse.weather.interval");
        requirePositive(openMeteoTimeout, "urbanpulse.weather.open-meteo-timeout");
        requirePositive(maxDelay, "urbanpulse.weather.max-delay");
        if (batchSize < 1) {
            throw new IllegalArgumentException("urbanpulse.weather.batch-size must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
