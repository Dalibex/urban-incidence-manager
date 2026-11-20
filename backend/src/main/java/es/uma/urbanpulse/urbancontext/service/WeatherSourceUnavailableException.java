package es.uma.urbanpulse.urbancontext.service;

/**
 * The weather source did not answer in time, or answered {@code 429} or {@code 5xx}. The context
 * stays {@code PENDING} and the next run tries again (ADR-0018, *Outcomes*).
 */
public class WeatherSourceUnavailableException extends RuntimeException {

    public WeatherSourceUnavailableException(String message) {
        super(message);
    }

    public WeatherSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
