package es.uma.urbanpulse.geospatial.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * The address search cannot answer because its source is down, too slow or answers what cannot be
 * read ({@code 503} {@code GEOCODING_UNAVAILABLE}). The report does not depend on it: the citizen
 * places the pin by hand (RF26).
 */
public class GeocodingUnavailableException extends ApiProblemException {

    public GeocodingUnavailableException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GEOCODING_UNAVAILABLE",
                "The address search is not available now. Place the pin on the map.");
    }
}
