package es.uma.urbanpulse.incidents.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code Idempotency-Key} a citizen's app sent with a report, and the hash of that report
 * (#289). A retry carries the same key and the same report; the same key with a different report is
 * another report that must not be confused with the first one. Both columns are empty for a report
 * sent without the header, and then the incident has no key.
 *
 * @param value the UUID the app created for the report
 * @param requestHash the hexadecimal SHA-256 of the report's data
 */
@Embeddable
public record IdempotencyKey(
        @Column(name = "idempotency_key") UUID value,
        @Column(name = "idempotency_request_hash") String requestHash) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(requestHash, "requestHash");
    }

    /** Whether a request with this key is a retry of the stored report, not a different one. */
    public boolean isSameRequest(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }
}
