package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.IncidentFilter;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.incidents.service.IncidentReportService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import es.uma.urbanpulse.shared.web.QueryParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * {@code listIncidents}, {@code getIncident}, {@code listIncidentStatusChanges} and {@code
 * createIncident}. The role is checked here; which incidents the caller may see, and which filters
 * a citizen may use, are checked by the service, which answers {@code 404} for the incidents the
 * caller may not see (docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/incidents")
@PreAuthorize("hasAnyRole('CITIZEN', 'MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class IncidentController {

    // The sortable properties and the default order of listIncidents in the contract.
    private static final Set<String> SORTABLE = Set.of("reportedAt", "updatedAt");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt");

    // Every query parameter of listIncidents in the contract; any other one is a 400.
    private static final Set<String> LIST_PARAMETERS =
            Set.of(
                    "page",
                    "size",
                    "sort",
                    "status",
                    "categoryId",
                    "priority",
                    "reportedFrom",
                    "reportedTo",
                    "departmentId");

    private final IncidentQueryService incidents;
    private final IncidentReportService incidentReportService;

    public IncidentController(
            IncidentReportService incidentReportService,
            IncidentQueryService incidentQueryService) {
        this.incidentReportService = incidentReportService;
        this.incidents = incidentQueryService;
    }

    @GetMapping
    PageResponse<IncidentSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<IncidentStatus> status,
            @RequestParam(required = false) List<UUID> categoryId,
            @RequestParam(required = false) List<Priority> priority,
            // ISO.DATE_TIME parses into OffsetDateTime only with an offset, so a local date-time
            // such as 2026-11-01T00:00 is a 400 instead of an instant in the server's zone.
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime reportedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime reportedTo,
            @RequestParam(required = false) List<UUID> departmentId) {
        QueryParameters.requireKnownAndFilled(parameters, LIST_PARAMETERS);
        var filter =
                new IncidentFilter(
                        asSet(status),
                        asSet(categoryId),
                        asSet(priority),
                        reportedFrom == null ? null : reportedFrom.toInstant(),
                        reportedTo == null ? null : reportedTo.toInstant(),
                        asSet(departmentId));
        return incidents.list(
                user.id(),
                user.role(),
                filter,
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

    @PostMapping
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<IncidentResponse> createIncident(
            @Valid @RequestBody CreateIncidentRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        var incident = incidentReportService.createIncident(request, currentUser.id());

        var response = IncidentResponse.of(incident, currentUser.role());

        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(incident.getId())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    // An absent repeatable parameter arrives as null.
    private static <T> Set<T> asSet(Collection<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
