package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Attachment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {}
