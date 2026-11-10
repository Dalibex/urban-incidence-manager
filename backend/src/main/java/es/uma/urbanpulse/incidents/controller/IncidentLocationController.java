package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.IncidentLocationListResponse;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.BoundingBox;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.QueryParameters;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listIncidentLocations}, the positions for the map. Only staff with a map may call it; the
 * service scopes a technician to their active assignments (docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/incident-locations")
@PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class IncidentLocationController {

    // Every query parameter of listIncidentLocations in the contract; any other one is a 400.
    private static final Set<String> PARAMETERS =
            Stream.concat(
                            Stream.of("minLatitude", "minLongitude", "maxLatitude", "maxLongitude"),
                            IncidentFilterParameters.NAMES.stream())
                    .collect(Collectors.toUnmodifiableSet());

    private final IncidentQueryService incidents;

    IncidentLocationController(IncidentQueryService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    IncidentLocationListResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(required = false) List<IncidentStatus> status,
            @RequestParam(required = false) List<UUID> categoryId,
            @RequestParam(required = false) List<Priority> priority,
            // Only with an offset, like listIncidents: a local date-time is a 400.
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime reportedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime reportedTo,
            @RequestParam(required = false) List<UUID> departmentId,
            @RequestParam(required = false) Double minLatitude,
            @RequestParam(required = false) Double minLongitude,
            @RequestParam(required = false) Double maxLatitude,
            @RequestParam(required = false) Double maxLongitude) {
        QueryParameters.requireKnownAndFilled(parameters, PARAMETERS);
        var filter =
                IncidentFilterParameters.toFilter(
                        status, categoryId, priority, reportedFrom, reportedTo, departmentId);
        var box = BoundingBox.fromLimits(minLatitude, minLongitude, maxLatitude, maxLongitude);
        return incidents.locations(user.id(), user.role(), filter, box);
    }
}
