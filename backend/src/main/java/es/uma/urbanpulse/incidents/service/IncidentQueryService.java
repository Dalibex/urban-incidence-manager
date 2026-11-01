package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.repository.LocationRepository;
import es.uma.urbanpulse.incidents.repository.StatusChangeRepository;
import es.uma.urbanpulse.shared.web.PageResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading incidents and their history. Every read goes through {@link IncidentReadAccess}, and the
 * response is built for the caller's role, so a citizen never receives staff-only data.
 */
@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

    private final IncidentRepository incidents;
    private final LocationRepository locations;
    private final StatusChangeRepository statusChanges;

    public IncidentQueryService(
            IncidentRepository incidents,
            LocationRepository locations,
            StatusChangeRepository statusChanges) {
        this.incidents = incidents;
        this.locations = locations;
        this.statusChanges = statusChanges;
    }

    /**
     * @param statuses only incidents in one of these; empty for every status
     */
    public PageResponse<IncidentSummaryResponse> list(
            UUID userId, Role role, Set<IncidentStatus> statuses, Pageable page) {
        var visible = IncidentReadAccess.visibleTo(userId, role);
        Specification<Incident> filter =
                statuses.isEmpty()
                        ? visible
                        : visible.and((incident, query, cb) -> incident.get("status").in(statuses));
        return PageResponse.of(
                incidents.findAll(filter, page),
                incident -> IncidentSummaryResponse.of(incident, role));
    }

    /**
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     */
    public IncidentResponse get(UUID incidentId, UUID userId, Role role) {
        var incident = visibleIncident(incidentId, userId, role);
        var location =
                locations
                        .findByIncidentId(incidentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Incident " + incidentId + " has no location"));
        return IncidentResponse.of(incident, location, role);
    }

    /**
     * The history, oldest first. A citizen only gets the entries visible to them.
     *
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     */
    public List<StatusChangeResponse> history(UUID incidentId, UUID userId, Role role) {
        visibleIncident(incidentId, userId, role);
        return statusChanges.findHistory(incidentId).stream()
                .filter(change -> role != Role.CITIZEN || change.isVisibleToCitizen())
                .map(change -> StatusChangeResponse.of(change, role))
                .toList();
    }

    private Incident visibleIncident(UUID incidentId, UUID userId, Role role) {
        return incidents
                .findOne(
                        IncidentReadAccess.visibleTo(userId, role)
                                .and(IncidentReadAccess.withId(incidentId)))
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }
}
