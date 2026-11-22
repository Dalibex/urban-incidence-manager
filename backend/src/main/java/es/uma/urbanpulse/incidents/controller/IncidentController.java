package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.incidents.service.IncidentReportService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.PageRequests;
import es.uma.urbanpulse.shared.web.PageResponse;
import es.uma.urbanpulse.shared.web.QueryParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * {@code listIncidents}, {@code getIncident}, {@code listIncidentStatusChanges} and {@code
 * createIncident}. The role is checked by the security filters ({@code ApiAuthorizationRules});
 * which incidents the caller may see, and which filters a citizen may use, are checked by the
 * service, which answers {@code 404} for the incidents the caller may not see (docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/incidents")
class IncidentController {

    // The sortable properties and the default order of listIncidents in the contract.
    private static final Set<String> SORTABLE = Set.of("reportedAt", "updatedAt");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "reportedAt");

    // The header of createIncident in the contract (#289), and the form of its value.
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern CANONICAL_UUID =
            Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Every query parameter of listIncidents in the contract; any other one is a 400. Only the
    // filters the contract declares as arrays may repeat.
    private static final Set<String> LIST_PARAMETERS =
            Stream.concat(
                            Stream.of("page", "size", "sort"),
                            IncidentFilterParameters.NAMES.stream())
                    .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> SINGLE_PARAMETERS =
            Stream.concat(
                            Stream.of("page", "size", "sort"),
                            IncidentFilterParameters.SINGLE_NAMES.stream())
                    .collect(Collectors.toUnmodifiableSet());

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
            @RequestParam(required = false) List<UUID> departmentId,
            @RequestParam(required = false) String q) {
        QueryParameters.requireKnownAndFilled(parameters, LIST_PARAMETERS);
        QueryParameters.requireSingle(parameters, SINGLE_PARAMETERS);
        var filter =
                IncidentFilterParameters.toFilter(
                        status, categoryId, priority, reportedFrom, reportedTo, departmentId, q);
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

    /**
     * A retry with the same {@code Idempotency-Key} answers {@code 201} with the incident already
     * created (#289), so the client handles a first answer and a repeated one in the same way.
     */
    @PostMapping
    public ResponseEntity<IncidentResponse> createIncident(
            @Valid @RequestBody CreateIncidentRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        var incident =
                idempotencyKey == null
                        ? incidentReportService.createIncident(request, currentUser.id())
                        : incidentReportService.createIncident(
                                request, currentUser.id(), parseIdempotencyKey(idempotencyKey));

        var response = IncidentResponse.of(incident, currentUser.role());

        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(incident.getId())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /**
     * The key in the canonical form the contract asks for. {@link UUID#fromString} alone also takes
     * shortened forms such as {@code 1-2-3-4-5}, so the same key could arrive written in two ways.
     */
    private static UUID parseIdempotencyKey(String value) {
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw new MalformedRequestException(
                    "Invalid value of the header " + IDEMPOTENCY_KEY + ".");
        }
        return UUID.fromString(value);
    }
}
