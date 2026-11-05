package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.CreateAssignmentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.service.IncidentAssignmentService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createIncidentAssignment}: the {@code ASSIGNMENT} transition of ADR-0020, also used to
 * reassign. Only operators and administrators make it, so the other roles get {@code 403} here; the
 * service applies the rest of the checks. The body has no {@code @Valid}: Bean Validation would run
 * before {@code @PreAuthorize} and answer {@code 422} to a role that should get {@code 403}.
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/assignments")
@PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'ADMINISTRATOR')")
class IncidentAssignmentController {

    private final IncidentAssignmentService assignments;

    IncidentAssignmentController(IncidentAssignmentService assignments) {
        this.assignments = assignments;
    }

    @PostMapping
    IncidentResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody CreateAssignmentRequest request) {
        return assignments.assign(incidentId, request, user.id(), user.role());
    }
}
