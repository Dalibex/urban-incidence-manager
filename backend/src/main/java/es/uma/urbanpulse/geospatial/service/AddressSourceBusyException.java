package es.uma.urbanpulse.geospatial.service;

import java.time.Duration;

/**
 * The address source was not called, because another call of the backend was too recent: the public
 * Nominatim allows one call a second for the whole application (#179).
 */
public class AddressSourceBusyException extends RuntimeException {

    private final Duration retryAfter;

    public AddressSourceBusyException(Duration retryAfter) {
        super("Another call to the address source is too recent");
        this.retryAfter = retryAfter;
    }

    /** How long until the source can be called again. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
