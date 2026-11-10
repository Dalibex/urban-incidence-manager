package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.AuditAction;
import es.uma.urbanpulse.identity.entity.AuditLog;
import es.uma.urbanpulse.identity.entity.AuditTargetType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/**
 * Append-only on purpose: it extends {@link Repository} instead of {@code JpaRepository}, so no
 * {@code delete} method exists. Add only inserts and reads here (the audit screen, #41, and the
 * classification changes, #47).
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    AuditLog save(AuditLog entry);

    Optional<AuditLog> findById(UUID id);

    /**
     * The entries of one action on one record, such as the classification changes of an incident
     * (#47), with their actor loaded in the same query.
     */
    @EntityGraph(attributePaths = "actor")
    Page<AuditLog> findByActionAndTargetTypeAndTargetId(
            AuditAction action, AuditTargetType targetType, UUID targetId, Pageable page);
}
