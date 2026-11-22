package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.CreateAssignmentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.service.IncidentAssignmentService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createIncidentAssignment}: the {@code ASSIGNMENT} transition of ADR-0020, also used to
 * reassign. Only operators and administrators make it: the security filters answer {@code 403} to
 * the other roles ({@code ApiAuthorizationRules}), and the service applies the rest of the checks.
 * The body has no {@code @Valid}: Bean Validation would answer {@code 422} before the service
 * answers {@code 404} for an incident the caller cannot see or {@code 409} for a stale version, and
 * ADR-0020 orders them {@code 403}, {@code 404}, {@code 409}, {@code 422}. The service validates
 * the body after those checks.
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/assignments")
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
