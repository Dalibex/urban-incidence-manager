package es.uma.urbanpulse.geospatial.service;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The address enrichment of #375 ({@code application.yaml}, docs/deployment.md).
 *
 * @param enabled whether the worker runs ({@code ADDRESS_ENRICHMENT_ENABLED}). Off, reports still
 *     save their address as {@code PENDING}
 * @param nominatimBaseUrl where Nominatim answers, so the tests point to a fake server
 * @param nominatimProxy HTTP forward proxy the calls go through ({@code NOMINATIM_PROXY}); null, or
 *     empty in the environment, calls Nominatim directly
 * @param nominatimUserAgent the {@code User-Agent} that identifies the application, as the
 *     Nominatim usage policy asks
 * @param interval time between two runs of the worker
 * @param batchSize locations per run
 * @param requestSpacing minimum time between two calls: the public Nominatim allows one call per
 *     second at most
 * @param nominatimTimeout connection and read timeout of each call
 * @param maxDelay from this age of the report on, a pending address becomes {@code UNAVAILABLE}
 */
@ConfigurationProperties("urbanpulse.address")
public record AddressEnrichmentProperties(
        boolean enabled,
        URI nominatimBaseUrl,
        URI nominatimProxy,
        String nominatimUserAgent,
        Duration interval,
        int batchSize,
        Duration requestSpacing,
        Duration nominatimTimeout,
        Duration maxDelay) {

    public AddressEnrichmentProperties {
        // An unset NOMINATIM_PROXY reaches the binder as an empty string.
        if (nominatimProxy != null && nominatimProxy.toString().isBlank()) {
            nominatimProxy = null;
        }
    }
}
