package es.uma.urbanpulse.incidents.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ClassificationChangeResponse}: one {@code CLASSIFY_INCIDENT} entry of the audit trail.
 *
 * @param before {@code ClassificationValues}: only the values that changed, in the order {@code
 *     category}, {@code priority}, {@code priorityReason}; a {@code null} value means it had none.
 *     A map, because "absent" (not changed) and {@code null} (no value) mean different things
 *     (docs/api.md, "Absent and null").
 * @param after the same, after the change
 */
public record ClassificationChangeResponse(
        UUID id,
        Instant instant,
        AuditActor actor,
        Map<String, Object> before,
        Map<String, Object> after) {

    public ClassificationChangeResponse {
        // Copied, not Map.copyOf: a null value is meaningful here.
        before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }

    /** {@code AuditActor}: who made the change, without a role (the trail does not store it). */
    public record AuditActor(UUID id, String name) {}
}
