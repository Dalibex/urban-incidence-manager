package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.entity.Attachment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** The photos of an incident, oldest first; the id breaks ties, so the order is stable. */
    List<Attachment> findByIncidentIdOrderByUploadedAtAscIdAsc(UUID incidentId);

    /** The photos of an incident that this user uploaded, in the same order (#284). */
    List<Attachment> findByIncidentIdAndUploaderIdOrderByUploadedAtAscIdAsc(
            UUID incidentId, UUID uploaderId);

    /**
     * The photos this user uploaded to any of these incidents, as {@code (incident id, attachment
     * id)} pairs: per incident, the oldest first with the id breaking ties, as in {@link
     * #findByIncidentIdAndUploaderIdOrderByUploadedAtAscIdAsc}. One query for a whole page (#279).
     */
    @Query(
            """
            select new es.uma.urbanpulse.incidents.repository.AttachmentRepository$OwnPhoto(
                a.incident.id, a.id)
            from Attachment a
            where a.incident.id in :incidentIds and a.uploader.id = :uploaderId
            order by a.uploadedAt asc, a.id asc
            """)
    List<OwnPhoto> findPhotosOfIncidentsUploadedBy(
            @Param("incidentIds") Collection<UUID> incidentIds,
            @Param("uploaderId") UUID uploaderId);

    record OwnPhoto(UUID incidentId, UUID attachmentId) {}

    /** The photo of an incident with this content, to recognise a retry (T13 of #67). */
    Optional<Attachment> findByIncidentIdAndContentHash(UUID incidentId, String contentHash);

    long countByIncidentId(UUID incidentId);
}
