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
import es.uma.urbanpulse.shared.text.Texts;
import es.uma.urbanpulse.shared.web.FileTooLargeException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The photos of an incident (ADR-0013). Who may read or add them is who may see the incident
 * ({@link IncidentReadAccess}), so an incident the caller may not see, or a photo of one, is a
 * {@code 404}; a citizen may only add them while the incident is {@code REPORTED}, and only sees
 * the photos they uploaded themselves (#284).
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

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
        var photos =
                role == Role.CITIZEN
                        ? attachments.findByIncidentIdAndUploaderIdOrderByUploadedAtAscIdAsc(
                                incidentId, userId)
                        : attachments.findByIncidentIdOrderByUploadedAtAscIdAsc(incidentId);
        return photos.stream().map(AttachmentResponse::of).toList();
    }

    /**
     * Checks the caller, the limits and the content, and looks for a retry with the same content,
     * which is answered without touching the storage. Then stores the file, outside any transaction
     * (AGENTS.md, section 6), and writes its row in a second one: if the storage fails, no row is
     * written.
     *
     * <p>Another upload or a status change may commit while the file is being stored, so the second
     * transaction locks the incident and repeats the checks before writing the row (#275). Of two
     * uploads at once, the second one waits for the first and then finds the same content, which it
     * answers as a retry, or no room left; if the incident left {@code REPORTED}, a citizen's
     * upload is refused. In every case the file it stored is removed.
     *
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     * @throws AttachmentNotAllowedException if a citizen adds it after {@code REPORTED}
     * @throws FileTooLargeException if it is over {@code EVIDENCE_MAX_FILE_SIZE}
     * @throws InvalidIncidentDataException if the file name holds U+0000, the content is not an
     *     accepted image, or the incident already has {@code EVIDENCE_MAX_FILES_PER_INCIDENT}
     *     photos
     */
    public Upload upload(UUID incidentId, UUID userId, Role role, String fileName, byte[] bytes) {
        var checked = inTransaction(() -> check(incidentId, userId, role, fileName, bytes));
        if (checked.existing() != null) {
            return new Upload(checked.existing(), false);
        }

        var content = checked.content();
        String key = storage.put(content.bytes());
        Upload upload;
        try {
            upload = inTransaction(() -> insert(incidentId, userId, role, fileName, content, key));
        } catch (RuntimeException e) {
            discard(key, e);
            throw e;
        }
        if (!upload.created()) {
            removeUnusedFile(key);
        }
        return upload;
    }

    /**
     * Removes the file of an upload answered with the photo another upload stored. The answer is
     * already right, so a failure only leaves a file no row points to, which is logged.
     */
    private void removeUnusedFile(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove the unused evidence file {}", key, e);
        }
    }

    /** What the checks found: the content to store, or the photo a retry already stored. */
    private record Checked(EvidenceContent content, AttachmentResponse existing) {}

    private Checked check(UUID incidentId, UUID userId, Role role, String fileName, byte[] bytes) {
        var incident = visibleIncident(incidentId, userId, role);
        requireOpenTo(role, incident);
        if (bytes.length > limits.maxFileSize().toBytes()) {
            throw new FileTooLargeException();
        }
        if (Texts.hasNullCharacter(fileName)) {
            throw InvalidIncidentDataException.invalidFileName();
        }
        var content = EvidenceContent.of(bytes);
        var existing = existing(incidentId, content.sha256());
        if (existing != null) {
            return new Checked(content, existing);
        }
        requireRoomIn(incidentId);
        if (!users.existsById(userId)) {
            throw new InsufficientAuthenticationException("The user does not exist");
        }
        return new Checked(content, null);
    }

    /** Writes the row of a stored file, after locking the incident and repeating the checks. */
    private Upload insert(
            UUID incidentId,
            UUID userId,
            Role role,
            String fileName,
            EvidenceContent content,
            String key) {
        var incident =
                incidents
                        .findLockedById(incidentId)
                        .filter(locked -> isVisible(incidentId, userId, role))
                        .orElseThrow(() -> new IncidentNotFoundException(incidentId));
        requireOpenTo(role, incident);
        var existing = existing(incidentId, content.sha256());
        if (existing != null) {
            return new Upload(existing, false);
        }
        requireRoomIn(incidentId);

        var attachment = new Attachment();
        attachment.setIncident(incident);
        attachment.setUploader(users.getReferenceById(userId));
        attachment.setFileName(fileName);
        attachment.setFileType(content.contentType());
        attachment.setFileSize(content.size());
        attachment.setContentHash(content.sha256());
        attachment.setStorageKey(key);
        attachment.setUploadedAt(OffsetDateTime.now(clock));
        return new Upload(AttachmentResponse.of(attachments.saveAndFlush(attachment)), true);
    }

    /** A citizen may only add photos while the incident is {@code REPORTED}. */
    private static void requireOpenTo(Role role, Incident incident) {
        if (role == Role.CITIZEN && incident.getStatus() != IncidentStatus.REPORTED) {
            throw new AttachmentNotAllowedException();
        }
    }

    private void requireRoomIn(UUID incidentId) {
        if (attachments.countByIncidentId(incidentId) >= limits.maxFilesPerIncident()) {
            throw InvalidIncidentDataException.tooManyFiles(limits.maxFilesPerIncident());
        }
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
     * @throws AttachmentNotFoundException if it does not exist, the caller may not see its
     *     incident, or the caller is a citizen who did not upload it
     */
    @Transactional(readOnly = true)
    public Photo content(UUID attachmentId, UUID userId, Role role) {
        var attachment =
                attachments
                        .findById(attachmentId)
                        .filter(found -> role != Role.CITIZEN || isUploadedBy(found, userId))
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

    private static boolean isUploadedBy(Attachment attachment, UUID userId) {
        return attachment.getUploader().getId().equals(userId);
    }

    private boolean isVisible(UUID incidentId, UUID userId, Role role) {
        return incidents.exists(
                IncidentReadAccess.visibleTo(userId, role)
                        .and(IncidentReadAccess.withId(incidentId)));
    }
}
