package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listIncidents}, {@code getIncident} and {@code listIncidentStatusChanges}. The role is
 * checked here; which incidents the caller may see is checked by the service, which answers {@code
 * 404} for the others (docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/incidents")
@PreAuthorize("hasAnyRole('CITIZEN', 'MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class IncidentController {

    // The sortable properties and the default order of listIncidents in the contract.
    private static final Set<String> SORTABLE = Set.of("reportedAt", "updatedAt");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt");

    private final IncidentQueryService incidents;

    IncidentController(IncidentQueryService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    PageResponse<IncidentSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<IncidentStatus> status) {
        var statuses =
                status == null || status.isEmpty()
                        ? EnumSet.noneOf(IncidentStatus.class)
                        : EnumSet.copyOf(status);
        return incidents.list(
                user.id(),
                user.role(),
                statuses,
                PageRequests.of(page, size, sort, SORTABLE, NEWEST_FIRST));
    }

    @GetMapping("/{incidentId}")
    IncidentResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID incidentId) {
        return incidents.get(incidentId, user.id(), user.role());
    }

    @GetMapping("/{incidentId}/status-changes")
    List<StatusChangeResponse> history(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID incidentId) {
        return incidents.history(incidentId, user.id(), user.role());
    }
}
