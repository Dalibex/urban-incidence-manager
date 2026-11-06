package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.IncidentActionRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.entity.IncidentTransition;
import es.uma.urbanpulse.incidents.entity.TransitionDetails;
import es.uma.urbanpulse.incidents.service.IncidentLifecycleService;
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
 * The lifecycle actions of an incident (ADR-0020), one sub-resource per transition. The role is
 * checked here ({@code 403}); {@link IncidentLifecycleService} answers {@code 404} for an incident
 * the caller cannot see and {@code 409} for a stale version or a transition the table does not
 * allow, and the domain answers {@code 422} for missing data, in that order.
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}")
class IncidentLifecycleController {

    private final IncidentLifecycleService lifecycle;

    IncidentLifecycleController(IncidentLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/validation")
    @PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'ADMINISTRATOR')")
    IncidentResponse validate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody IncidentActionRequest request) {
        return change(user, incidentId, request, IncidentTransition.VALIDATION);
    }

    @PostMapping("/rejection")
    @PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'ADMINISTRATOR')")
    IncidentResponse reject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody IncidentActionRequest request) {
        return change(user, incidentId, request, IncidentTransition.REJECTION);
    }

    @PostMapping("/acceptance")
    @PreAuthorize("hasRole('TECHNICIAN')")
    IncidentResponse accept(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody IncidentActionRequest request) {
        return change(user, incidentId, request, IncidentTransition.ACCEPTANCE);
    }

    @PostMapping("/resolution")
    @PreAuthorize("hasRole('TECHNICIAN')")
    IncidentResponse resolve(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID incidentId,
            @RequestBody IncidentActionRequest request) {
        return change(user, incidentId, request, IncidentTransition.RESOLUTION);
    }

    private IncidentResponse change(
            AuthenticatedUser user,
            UUID incidentId,
            IncidentActionRequest request,
            IncidentTransition transition) {
        return lifecycle.changeStatus(
                incidentId,
                request.version(),
                transition,
                TransitionDetails.withReason(request.reason()),
                user.id(),
                user.role());
    }
}
