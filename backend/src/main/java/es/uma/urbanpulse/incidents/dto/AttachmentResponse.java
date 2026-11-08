package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.Attachment;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code AttachmentResponse}: the metadata of a stored photo. It never holds the storage key or a
 * public URL (ADR-0013); the file is read with {@code getAttachmentContent}, after authorisation.
 *
 * @param fileName name of the file on the device, only to show it; never used as a path
 * @param contentType the type detected from the content
 * @param size size of the file in bytes
 */
public record AttachmentResponse(
        UUID id, String fileName, String contentType, long size, Instant uploadedAt) {

    public static AttachmentResponse of(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getFileType(),
                attachment.getFileSize(),
                attachment.getUploadedAt().toInstant());
    }
}
