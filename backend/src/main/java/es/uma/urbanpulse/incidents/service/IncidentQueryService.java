package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.dto.IncidentLocationListResponse;
import es.uma.urbanpulse.incidents.dto.IncidentResponse;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse;
import es.uma.urbanpulse.incidents.dto.IncidentSummaryResponse;
import es.uma.urbanpulse.incidents.dto.StatusChangeResponse;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.repository.IncidentRepository;
import es.uma.urbanpulse.incidents.repository.StatusChangeRepository;
import es.uma.urbanpulse.shared.web.PageResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading incidents and their history. Every read goes through {@link IncidentReadAccess}, and the
 * response is built for the caller's role, so a citizen never receives staff-only data.
 */
@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

    /**
     * The most positions {@code listIncidentLocations} returns. The map groups them on the client,
     * and the whole city fits well below it with the demo data; with more, the most relevant win.
     */
    public static final int MAX_LOCATIONS = 1000;

    private final IncidentRepository incidents;
    private final StatusChangeRepository statusChanges;

    public IncidentQueryService(
            IncidentRepository incidents, StatusChangeRepository statusChanges) {
        this.incidents = incidents;
        this.statusChanges = statusChanges;
    }

    /**
     * @throws AccessDeniedException if a citizen uses a staff filter: ignoring it would give a
     *     misleading list, and accepting {@code priority} would reveal a staff-only value
     */
    public PageResponse<IncidentSummaryResponse> list(
            UUID userId, Role role, IncidentFilter filter, Pageable page) {
        if (role == Role.CITIZEN && filter.usesStaffFilters()) {
            throw new AccessDeniedException("Citizens can only filter their reports by status.");
        }
        var visible = IncidentReadAccess.visibleTo(userId, role).and(filter.specification());
        return PageResponse.of(
                incidents.findAll(visible, page),
                incident -> IncidentSummaryResponse.of(incident, role));
    }

    /**
     * The positions for the map, with the same scope and filters as {@link #list}, the most
     * relevant first and no more than {@link #MAX_LOCATIONS}. It is not paginated, because the map
     * needs one answer for the whole view. The controller only lets staff in; a citizen has no map.
     *
     * @param box empty for every position, whatever its place
     */
    public IncidentLocationListResponse locations(
            UUID userId, Role role, IncidentFilter filter, Optional<BoundingBox> box) {
        var matching = IncidentReadAccess.visibleTo(userId, role).and(filter.specification());
        if (box.isPresent()) {
            matching = matching.and(box.get().specification());
        }
        // One more than the cap tells whether some were left out, without a count query.
        var positions = incidents.findPositions(matching, MAX_LOCATIONS + 1);
        var truncated = positions.size() > MAX_LOCATIONS;
        return new IncidentLocationListResponse(
                truncated ? positions.subList(0, MAX_LOCATIONS) : positions, truncated);
    }

    /**
     * The counts of the incidents the map would draw, with the same scope and filters as {@link
     * #locations} but without a box or a cap. The controller only lets staff in.
     */
    // Four queries, one per dimension: REPEATABLE_READ gives them one snapshot, so an incident that
    // changes meanwhile is in the same group in all of them and each list adds up to the total.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IncidentStatisticsResponse statistics(UUID userId, Role role, IncidentFilter filter) {
        var matching = IncidentReadAccess.visibleTo(userId, role).and(filter.specification());
        return IncidentStatisticsAssembler.assemble(
                filter,
                incidents.countByStatus(matching),
                incidents.countByPriority(matching),
                incidents.countByCategory(matching),
                incidents.countByDepartment(matching));
    }

    /**
     * @throws IncidentNotFoundException if it does not exist or the caller may not see it
     */
    public IncidentResponse get(UUID incidentId, UUID userId, Role role) {
        return IncidentResponse.of(visibleIncident(incidentId, userId, role), role);
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
