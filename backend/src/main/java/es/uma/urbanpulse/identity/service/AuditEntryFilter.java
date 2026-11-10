package es.uma.urbanpulse.identity.service;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The filters of {@code listAuditEntries} ({@code docs/api/openapi.yml}). A {@code null} value or
 * an empty set means that filter is not used.
 *
 * @param from only entries at or after this instant
 * @param to only entries before this instant, so consecutive ranges never count an entry twice
 * @param actorId only entries made by this user
 * @param actions only entries of one of these actions
 */
public record AuditEntryFilter(Instant from, Instant to, UUID actorId, Set<AuditAction> actions) {

    /**
     * @throws MalformedRequestException if {@code from} is not earlier than {@code to}
     */
    public AuditEntryFilter {
        actions = Set.copyOf(actions);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new MalformedRequestException("from must be earlier than to.");
        }
    }
}
