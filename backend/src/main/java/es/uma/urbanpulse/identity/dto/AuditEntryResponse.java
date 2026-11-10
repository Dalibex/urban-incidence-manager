package es.uma.urbanpulse.identity.dto;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditTargetType;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code AuditEntryResponse}: one entry of the audit trail (RF18) for the audit log screen (#41).
 *
 * @param target {@code null} when the action is not about a single record
 * @param details {@code null} when the entry has no details
 */
public record AuditEntryResponse(
        UUID id, Instant instant, Actor actor, AuditAction action, Target target, Details details) {

    /** Who made the change, with their current name; no role, which the trail does not store. */
    public record Actor(UUID id, String name) {}

    /**
     * The record the entry is about.
     *
     * @param label current name of the user or title of the incident; {@code null} when the record
     *     no longer exists
     */
    public record Target(AuditTargetType type, UUID id, String label) {}

    /**
     * The values the change replaced and the ones it set, by field, in the order they were
     * recorded. A field with no value keeps its {@code null}, so these maps may hold {@code null}
     * values; a missing side is empty.
     */
    public record Details(Map<String, Object> before, Map<String, Object> after) {

        // Map.copyOf rejects the null value of a field that had none, so the map is copied by hand.
        public Details {
            before =
                    Collections.unmodifiableMap(
                            before == null ? new LinkedHashMap<>() : new LinkedHashMap<>(before));
            after =
                    Collections.unmodifiableMap(
                            after == null ? new LinkedHashMap<>() : new LinkedHashMap<>(after));
        }
    }
}
