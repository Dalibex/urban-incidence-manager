package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.ClassificationChangeResponse;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.UpdateIncidentRequest;
import es.uma.urbanpulse.incidents.service.IncidentClassificationService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code updateIncident} and {@code listIncidentClassificationChanges} (#47): the category and the
 * priority of an incident, and their changes. The role is checked here; the service checks the
 * rest. The body has no {@code @Valid}: Bean Validation would run before {@code @PreAuthorize} and
 * answer {@code 422} to a role that should get {@code 403} (ADR-0020).
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}")
class IncidentClassificationController {

    // The sortable property and the default order of listIncidentClassificationChanges.
    private static final Set<String> SORTABLE = Set.of("instant");
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Direction.ASC, "instant");

    private final IncidentClassificationService classification;

    IncidentClassificationController(IncidentClassificationService classification) {
        this.classification = classification;
    }

    @PatchMapping
    @PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'ADMINISTRATOR')")
    IncidentResponse update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody UpdateIncidentRequest request) {
        return classification.classify(incidentId, request, user.id(), user.role());
    }

    @GetMapping("/classification-changes")
    @PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
    PageResponse<ClassificationChangeResponse> changes(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        return classification.changes(
                incidentId,
                user.id(),
                user.role(),
                PageRequests.of(page, size, sort, SORTABLE, OLDEST_FIRST));
    }
}
