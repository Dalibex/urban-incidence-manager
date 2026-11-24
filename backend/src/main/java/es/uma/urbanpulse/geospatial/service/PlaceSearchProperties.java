package es.uma.urbanpulse.geospatial.service;

import es.uma.urbanpulse.identity.service.AccountSecurityProperties.AttemptLimit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The address search of #179 ({@code application.yaml}). The calls to Nominatim are spaced by
 * {@code urbanpulse.address.request-spacing}, shared with the address worker.
 *
 * @param searchesPerUser searches each user may make in each window, so one user cannot use up the
 *     one call a second to Nominatim that every user shares
 * @param cacheTtl how long a search is answered from the cache, as the Nominatim usage policy asks
 *     of repeated searches
 * @param cacheSize the most searches the cache keeps; the least recently used goes first
 */
@ConfigurationProperties("urbanpulse.places")
public record PlaceSearchProperties(
        AttemptLimit searchesPerUser, Duration cacheTtl, int cacheSize) {

    public PlaceSearchProperties {
        if (searchesPerUser == null) {
            throw new IllegalArgumentException("urbanpulse.places.searches-per-user is required");
        }
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("urbanpulse.places.cache-ttl must be positive");
        }
        if (cacheSize < 1) {
            throw new IllegalArgumentException("urbanpulse.places.cache-size must be at least 1");
        }
    }
}
