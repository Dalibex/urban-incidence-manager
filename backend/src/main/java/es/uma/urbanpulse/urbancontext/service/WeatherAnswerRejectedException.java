package es.uma.urbanpulse.urbancontext.service;

/**
 * The weather source refused the request ({@code 4xx} other than {@code 429}) or sent an answer
 * that cannot be read. The same request would fail again, so the context is {@code ERROR}
 * (ADR-0018, *Outcomes*).
 */
public class WeatherAnswerRejectedException extends RuntimeException {

    public WeatherAnswerRejectedException(String message) {
        super(message);
    }

    public WeatherAnswerRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
