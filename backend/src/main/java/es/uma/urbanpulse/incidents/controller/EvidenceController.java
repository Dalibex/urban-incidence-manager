package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.AttachmentResponse;
import es.uma.urbanpulse.incidents.service.EvidenceService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * {@code listIncidentAttachments}, {@code createIncidentAttachment} and {@code
 * getAttachmentContent}: the photos of an incident (ADR-0013). The role is checked here; which
 * incidents the caller may see, and when a citizen may add photos, is checked by {@link
 * EvidenceService}.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('CITIZEN', 'MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class EvidenceController {

    private final EvidenceService evidence;

    EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/incidents/{incidentId}/attachments")
    List<AttachmentResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID incidentId) {
        return evidence.list(incidentId, user.id(), user.role());
    }

    /** {@code 201} with the new photo, or {@code 200} with the one a retry found (no Location). */
    @PostMapping(
            path = "/incidents/{incidentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AttachmentResponse> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        var upload =
                evidence.upload(
                        incidentId,
                        user.id(),
                        user.role(),
                        Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                        file.getBytes());
        if (!upload.created()) {
            return ResponseEntity.ok(upload.attachment());
        }
        var location =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/attachments/{id}/content")
                        .buildAndExpand(upload.attachment().id())
                        .toUri();
        return ResponseEntity.created(location).body(upload.attachment());
    }

    @GetMapping("/attachments/{attachmentId}/content")
    ResponseEntity<Resource> content(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID attachmentId) {
        var photo = evidence.content(attachmentId, user.id(), user.role());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.content());
    }
}
