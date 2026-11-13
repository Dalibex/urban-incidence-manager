package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.web.QueryParameters;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getIncidentStatistics}, the counts of the staff dashboard. It has the filters and the
 * scope of {@code listIncidentLocations}, so it counts what the map draws (docs/security.md).
 */
@RestController
@RequestMapping("/api/v1/incident-statistics")
@PreAuthorize("hasAnyRole('MUNICIPAL_OPERATOR', 'TECHNICIAN', 'ADMINISTRATOR')")
class IncidentStatisticsController {

    private final IncidentQueryService incidents;

    IncidentStatisticsController(IncidentQueryService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    IncidentStatisticsResponse statistics(
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
            @RequestParam(required = false) List<UUID> departmentId) {
        QueryParameters.requireKnownAndFilled(parameters, IncidentFilterParameters.NAMES);
        var filter =
                IncidentFilterParameters.toFilter(
                        status, categoryId, priority, reportedFrom, reportedTo, departmentId);
        return incidents.statistics(user.id(), user.role(), filter);
    }
}
