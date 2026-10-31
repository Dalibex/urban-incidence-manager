package es.uma.urbanpulse.shared.web;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * An expected failure of a use case that the API answers with a Problem Details body and a stable
 * {@code code} from the catalogue in {@code docs/api.md}. Each module subclasses it for its own
 * errors (for example, {@code EMAIL_ALREADY_EXISTS}); {@link ApiExceptionHandler} writes the
 * response.
 *
 * <p>The detail is shown to the client, so it never carries personal data, SQL or internal ids.
 */
public abstract class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiProblemException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** How long the client should wait before retrying ({@code Retry-After}), if it applies. */
    public Optional<Duration> retryAfter() {
        return Optional.empty();
    }

    /** The invalid fields of a {@code 422}, written as {@code errors}; empty for other errors. */
    public List<FieldError> fieldErrors() {
        return List.of();
    }
}
