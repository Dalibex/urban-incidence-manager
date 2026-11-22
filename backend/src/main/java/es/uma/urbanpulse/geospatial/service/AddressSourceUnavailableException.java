package es.uma.urbanpulse.geospatial.service;

/**
 * The address source did not answer in time, or answered {@code 429} or {@code 5xx}. The address
 * stays {@code PENDING} and the next run tries again (#375).
 */
public class AddressSourceUnavailableException extends RuntimeException {

    public AddressSourceUnavailableException(String message) {
        super(message);
    }
}
