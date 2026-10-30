package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/** Rate limit reached on sign-in or sign-up ({@code 429} with {@code Retry-After}). */
public class TooManyAttemptsException extends ApiProblemException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "TOO_MANY_ATTEMPTS",
                "Too many attempts. Try again later.");
        this.retryAfter = retryAfter;
    }

    @Override
    public Optional<Duration> retryAfter() {
        return Optional.of(retryAfter);
    }
}
