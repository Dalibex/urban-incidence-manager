package es.uma.urbanpulse.geospatial.service;

import es.uma.urbanpulse.geospatial.dto.PlaceResponse;
import es.uma.urbanpulse.identity.service.AttemptLimiter;
import es.uma.urbanpulse.identity.service.TooManyAttemptsException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code listPlaces}: the address search of the report form (#179, RF04). The places come from
 * Nominatim (ADR-0002) through {@link PlaceProvider}, bounded to the municipality of Málaga.
 *
 * <p>Two limits protect the one call a second that Nominatim allows the whole application: each
 * user may search {@code urbanpulse.places.searches-per-user} times in each window, and a search
 * waits at most {@code urbanpulse.address.request-spacing} for its turn to call Nominatim or is
 * answered {@code 429}, which does not count against the user. A repeated search is answered from
 * {@link PlaceSearchCache} without calling Nominatim. A failing source is {@code 503} {@code
 * GEOCODING_UNAVAILABLE}: the report never depends on it (RF26).
 *
 * <p>The search follows the switch of the address enrichment ({@code ADDRESS_ENRICHMENT_ENABLED}):
 * off, it is {@code 503} without calling Nominatim, so a deployment has one way to send nothing to
 * OpenStreetMap.
 */
@Service
public class PlaceSearchService {

    /** The most places one search returns ({@code maxItems} of the contract). */
    public static final int MAX_PLACES = 5;

    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);

    private final PlaceProvider provider;
    private final boolean enabled;
    private final AttemptLimiter searchesPerUser;
    private final PlaceSearchCache cache;

    public PlaceSearchService(
            PlaceProvider provider,
            PlaceSearchProperties properties,
            AddressEnrichmentProperties addressEnrichment,
            Clock clock) {
        this.provider = provider;
        this.enabled = addressEnrichment.enabled();
        this.searchesPerUser = new AttemptLimiter(properties.searchesPerUser(), clock);
        this.cache = new PlaceSearchCache(properties.cacheTtl(), properties.cacheSize(), clock);
    }

    /**
     * @param query the text to search, already stripped and within the limits of the contract
     * @param userId who searches, for the limit of searches per user
     * @return the places of Málaga that match, the most relevant first; empty when none does
     * @throws TooManyAttemptsException if the user reached the limit, or the turn to call Nominatim
     *     is further away than {@code urbanpulse.address.request-spacing}
     * @throws GeocodingUnavailableException if the address enrichment is switched off, or Nominatim
     *     does not answer in time or answers an error
     */
    public List<PlaceResponse> search(String query, UUID userId) {
        if (!enabled) {
            throw new GeocodingUnavailableException();
        }
        var key = userId.toString();
        searchesPerUser.acquire(key);
        List<Place> places;
        try {
            places = cache.get(query).orElseGet(() -> searchTheSource(query));
        } catch (TooManyAttemptsException e) {
            // Refused because Nominatim was busy, not because of this user: the search is theirs
            // again.
            searchesPerUser.giveBack(key);
            throw e;
        }
        return places.stream()
                .map(
                        place ->
                                new PlaceResponse(
                                        place.displayName(), place.latitude(), place.longitude()))
                .toList();
    }

    private List<Place> searchTheSource(String query) {
        List<Place> places;
        try {
            places = provider.search(query, MAX_PLACES);
        } catch (AddressSourceBusyException e) {
            throw new TooManyAttemptsException(e.retryAfter());
        } catch (AddressSourceUnavailableException | AddressAnswerRejectedException e) {
            // The text the user searched is not written to the log: it can be their address.
            log.warn("Address search failed: {}", e.getMessage());
            throw new GeocodingUnavailableException();
        }
        cache.put(query, places);
        return places;
    }
}
