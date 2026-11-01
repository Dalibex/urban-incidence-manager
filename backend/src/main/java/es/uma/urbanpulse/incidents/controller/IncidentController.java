package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.identity.entity.User;
import es.uma.urbanpulse.identity.repository.UserRepository;
import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.incidents.service.IncidentService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * {@code listIncidents}, {@code getIncident} and {@code listIncidentStatusChanges}. The role is
 * checked here; which incidents the caller may see is checked by the service, which answers {@code
 * 404} for the others (docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/incidents")
@PreAuthorize("hasAnyRole('CITIZEN', 'MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
public class IncidentController {

    // The sortable properties and the default order of listIncidents in the contract.
    private static final Set<String> SORTABLE = Set.of("reportedAt", "updatedAt");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt");

    private final IncidentService incidentService;
    private final IncidentQueryService incidentQueryService;
    private final UserRepository users;

    IncidentController(
            IncidentService incidentService,
            IncidentQueryService incidentQueryService,
            UserRepository users) {
        this.incidentService = incidentService;
        this.incidentQueryService = incidentQueryService;
        this.users = users;
    }

    @PostMapping
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<IncidentResponse> createIncident(
            @Valid @RequestBody CreateIncidentRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        User reporter =
                users.findById(currentUser.id())
                        .orElseThrow(
                                () ->
                                        new org.springframework.security.authentication
                                                .InsufficientAuthenticationException(
                                                "The user does not exist"));
        var incident = incidentService.createIncident(request, reporter);
        var response = IncidentResponse.of(incident, incident.getLocation(), reporter.getRole());
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(incident.getId())
                        .toUri();
        return ResponseEntity.created(location).body(response);
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
        return incidentQueryService.list(
                user.id(),
                user.role(),
                statuses,
                PageRequests.of(page, size, sort, SORTABLE, NEWEST_FIRST));
    }

    @GetMapping("/{incidentId}")
    IncidentResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID incidentId) {
        return incidentQueryService.get(incidentId, user.id(), user.role());
    }

    @GetMapping("/{incidentId}/status-changes")
    List<StatusChangeResponse> history(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID incidentId) {
        return incidentQueryService.history(incidentId, user.id(), user.role());
    }
}