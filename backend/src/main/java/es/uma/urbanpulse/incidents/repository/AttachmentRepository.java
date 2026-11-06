package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Attachment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** The photos of an incident, oldest first; the id breaks ties, so the order is stable. */
    List<Attachment> findByIncidentIdOrderByUploadedAtAscIdAsc(UUID incidentId);

    /** The photo of an incident with this content, to recognise a retry (T13 of #67). */
    Optional<Attachment> findByIncidentIdAndContentHash(UUID incidentId, String contentHash);

    long countByIncidentId(UUID incidentId);
}
