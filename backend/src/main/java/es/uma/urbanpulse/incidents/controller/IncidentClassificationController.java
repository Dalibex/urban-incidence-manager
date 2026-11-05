package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.UpdateIncidentRequest;
import es.uma.urbanpulse.incidents.service.IncidentClassificationService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code updateIncident} (#47): the category and the priority of an incident. The role is checked
 * here; the service checks the rest. The body has no {@code @Valid}: Bean Validation would run
 * before {@code @PreAuthorize} and answer {@code 422} to a role that should get {@code 403}
 * (ADR-0020).
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}")
class IncidentClassificationController {

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
}
