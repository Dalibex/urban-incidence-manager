package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.AuditLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Append-only on purpose: it extends {@link Repository} instead of {@code JpaRepository}, so no
 * {@code delete} method exists. Add only inserts and reads here (the audit screen, #41).
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    AuditLog save(AuditLog entry);

    Optional<AuditLog> findById(UUID id);
}
