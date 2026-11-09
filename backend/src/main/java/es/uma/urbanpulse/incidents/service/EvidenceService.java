package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.AttachmentResponse;
import es.uma.urbanpulse.incidents.entity.Attachment;
import es.uma.urbanpulse.incidents.entity.EvidenceContent;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.InvalidIncidentDataException;
import es.uma.urbanpulse.incidents.repository.AttachmentRepository;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.shared.storage.EvidenceProperties;
import es.uma.urbanpulse.shared.storage.StorageService;
import es.uma.urbanpulse.shared.web.FileTooLargeException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The photos of an incident (ADR-0013). Who may read or add them is who may see the incident
 * ({@link IncidentReadAccess}), so an incident the caller may not see, or a photo of one, is a
 * {@code 404}; a citizen may only add them while the incident is {@code REPORTED}.
 */
@Service
public class EvidenceService {

    private final IncidentRepository incidents;
    private final AttachmentRepository attachments;
    private final UserRepository users;
    private final StorageService storage;
    private final EvidenceProperties limits;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public EvidenceService(
            IncidentRepository incidents,
            AttachmentRepository attachments,
            UserRepository users,
            StorageService storage,
            EvidenceProperties limits,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.attachments = attachments;
        this.users = users;
        this.storage = storage;
        this.limits = limits;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** The result of an upload: a new photo, or the one a retry with the same content found. */
    public record Upload(AttachmentResponse attachment, boolean created) {}

    /** A photo to send to the client, with the type detected when it was uploaded. */
    public record Photo(String contentType, Resource content) {}

    /**
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     */
    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(UUID incidentId, UUID userId, Role role) {
        visibleIncident(incidentId, userId, role);
        return attachments.findByIncidentIdOrderByUploadedAtAscIdAsc(incidentId).stream()
                .map(AttachmentResponse::of)
                .toList();
    }

    /**
     * Checks the caller, the limits and the content, and looks for a retry with the same content,
     * which is answered without touching the storage. Then stores the file, outside any transaction
     * (AGENTS.md, section 6), and writes its row in a second one: if the storage fails, no row is
     * written.
     *
     * <p>Two uploads of the same content at the same time both pass the lookup; the unique index of
     * {@code V13} keeps only the first row, and the second request removes its file and answers
     * with the first photo, as a retry.
     *
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     * @throws AttachmentNotAllowedException if a citizen adds it after {@code REPORTED}
     * @throws FileTooLargeException if it is over {@code EVIDENCE_MAX_FILE_SIZE}
     * @throws InvalidIncidentDataException if the content is not an accepted image, or the incident
     *     already has {@code EVIDENCE_MAX_FILES_PER_INCIDENT} photos
     */
    public Upload upload(UUID incidentId, UUID userId, Role role, String fileName, byte[] bytes) {
        var checked = inTransaction(() -> check(incidentId, userId, role, bytes));
        if (checked.existing() != null) {
            return new Upload(checked.existing(), false);
        }

        var content = checked.content();
        String key = storage.put(content.bytes());
        try {
            return new Upload(
                    inTransaction(() -> insert(incidentId, userId, fileName, content, key)), true);
        } catch (RuntimeException e) {
            discard(key, e);
            if (e instanceof DataIntegrityViolationException) {
                var winner = inTransaction(() -> existing(incidentId, content.sha256()));
                if (winner != null) {
                    return new Upload(winner, false);
                }
            }
            throw e;
        }
    }

    /** What the checks found: the content to store, or the photo a retry already stored. */
    private record Checked(EvidenceContent content, AttachmentResponse existing) {}

    private Checked check(UUID incidentId, UUID userId, Role role, byte[] bytes) {
        var incident = visibleIncident(incidentId, userId, role);
        if (role == Role.CITIZEN && incident.getStatus() != IncidentStatus.REPORTED) {
            throw new AttachmentNotAllowedException();
        }
        if (bytes.length > limits.maxFileSize().toBytes()) {
            throw new FileTooLargeException();
        }
        var content = EvidenceContent.of(bytes);
        var existing = existing(incidentId, content.sha256());
        if (existing != null) {
            return new Checked(content, existing);
        }
        if (attachments.countByIncidentId(incidentId) >= limits.maxFilesPerIncident()) {
            throw InvalidIncidentDataException.tooManyFiles(limits.maxFilesPerIncident());
        }
        if (!users.existsById(userId)) {
            throw new InsufficientAuthenticationException("The user does not exist");
        }
        return new Checked(content, null);
    }

    private AttachmentResponse insert(
            UUID incidentId, UUID userId, String fileName, EvidenceContent content, String key) {
        var attachment = new Attachment();
        attachment.setIncident(incidents.getReferenceById(incidentId));
        attachment.setUploader(users.getReferenceById(userId));
        attachment.setFileName(fileName);
        attachment.setFileType(content.contentType());
        attachment.setFileSize(content.size());
        attachment.setContentHash(content.sha256());
        attachment.setStorageKey(key);
        attachment.setUploadedAt(OffsetDateTime.now(clock));
        return AttachmentResponse.of(attachments.saveAndFlush(attachment));
    }

    /** The photo of the incident with this content, or {@code null}. */
    private AttachmentResponse existing(UUID incidentId, String contentHash) {
        return attachments
                .findByIncidentIdAndContentHash(incidentId, contentHash)
                .map(AttachmentResponse::of)
                .orElse(null);
    }

    /** Removes a stored file whose row was not written; a failure to do so is kept in {@code e}. */
    private void discard(String key, RuntimeException e) {
        try {
            storage.delete(key);
        } catch (RuntimeException deleteFailure) {
            e.addSuppressed(deleteFailure);
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /**
     * @throws AttachmentNotFoundException if it does not exist or the caller may not see its
     *     incident
     */
    @Transactional(readOnly = true)
    public Photo content(UUID attachmentId, UUID userId, Role role) {
        var attachment =
                attachments
                        .findById(attachmentId)
                        .filter(found -> isVisible(found.getIncident().getId(), userId, role))
                        .orElseThrow(() -> new AttachmentNotFoundException(attachmentId));
        return new Photo(
                attachment.getFileType(),
                new ByteArrayResource(storage.get(attachment.getStorageKey())));
    }

    private Incident visibleIncident(UUID incidentId, UUID userId, Role role) {
        return incidents
                .findOne(
                        IncidentReadAccess.visibleTo(userId, role)
                                .and(IncidentReadAccess.withId(incidentId)))
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }

    private boolean isVisible(UUID incidentId, UUID userId, Role role) {
        return incidents.exists(
                IncidentReadAccess.visibleTo(userId, role)
                        .and(IncidentReadAccess.withId(incidentId)));
    }
}
