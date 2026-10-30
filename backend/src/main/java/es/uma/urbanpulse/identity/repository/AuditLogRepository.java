package es.uma.urbanpulse.identity.repository;

import es.uma.urbanpulse.identity.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {}
