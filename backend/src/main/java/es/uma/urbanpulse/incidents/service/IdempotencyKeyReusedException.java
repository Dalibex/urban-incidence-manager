package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;

/**
 * The citizen already used this {@code Idempotency-Key} for a different report (#289). Answering
 * with the stored incident would make the client believe its new report was saved, so it answers
 * {@code 422} with {@code IDEMPOTENCY_KEY_REUSED} ({@code docs/api.md}) and creates nothing.
 */
public class IdempotencyKeyReusedException extends ApiProblemException {

    static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    IdempotencyKeyReusedException() {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                CODE,
                "This Idempotency-Key was already used for a different report. Send a new key for a"
                        + " new report.");
    }
}
