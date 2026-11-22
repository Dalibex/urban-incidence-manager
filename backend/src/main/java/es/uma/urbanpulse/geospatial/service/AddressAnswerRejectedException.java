package es.uma.urbanpulse.geospatial.service;

/**
 * The address source refused the request ({@code 4xx} other than {@code 429}), or its answer cannot
 * be read. The same request would fail again, so the address becomes {@code UNAVAILABLE} (#375).
 */
public class AddressAnswerRejectedException extends RuntimeException {

    public AddressAnswerRejectedException(String message) {
        super(message);
    }

    public AddressAnswerRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
