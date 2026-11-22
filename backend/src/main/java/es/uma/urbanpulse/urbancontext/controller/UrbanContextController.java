package es.uma.urbanpulse.urbancontext.controller;

import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.urbancontext.dto.UrbanContextResponse;
import es.uma.urbanpulse.urbancontext.service.UrbanContextQueryService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getIncidentUrbanContext}. The context is for staff who handle incidents: a citizen gets
 * {@code 403}, also for their own incident. Which incidents each one may see is checked by {@link
 * UrbanContextQueryService}.
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/urban-context")
class UrbanContextController {

    private final UrbanContextQueryService contexts;

    UrbanContextController(UrbanContextQueryService contexts) {
        this.contexts = contexts;
    }

    @GetMapping
    UrbanContextResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID incidentId) {
        return contexts.get(incidentId, user.id(), user.role());
    }
}
