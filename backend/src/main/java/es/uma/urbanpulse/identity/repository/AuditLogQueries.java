package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read-only queries of the audit trail that a derived query cannot express, because each filter may
 * be absent. Spring Data adds {@link AuditLogQueriesImpl} to {@link AuditLogRepository}.
 */
public interface AuditLogQueries {

    /**
     * One page of the entries that match every filter given, with their actor loaded in the same
     * query. A {@code null} value or an empty set does not filter.
     *
     * @param from only entries at or after this instant
     * @param to only entries before this instant
     * @param actorId only entries made by this user
     * @param actions only entries of one of these actions
     * @param page the page, ordered by the properties of {@link AuditLog} its sort names
     */
    Page<AuditLog> findMatching(
            Instant from, Instant to, UUID actorId, Set<AuditAction> actions, Pageable page);
}
