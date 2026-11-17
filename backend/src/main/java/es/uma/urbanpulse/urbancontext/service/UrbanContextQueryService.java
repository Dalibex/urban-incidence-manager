package es.uma.urbanpulse.urbancontext.service;

import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.incidents.service.IncidentNotFoundException;
import es.uma.urbanpulse.incidents.service.IncidentQueryService;
import es.uma.urbanpulse.urbancontext.dto.UrbanContextResponse;
import es.uma.urbanpulse.urbancontext.repository.UrbanContextRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the urban context of an incident. It only reads the database, so a source that is down
 * never changes the answer, and it applies the access rule of the incident itself.
 */
@Service
@Transactional(readOnly = true)
public class UrbanContextQueryService {

    private final IncidentQueryService incidents;
    private final UrbanContextRepository contexts;

    public UrbanContextQueryService(
            IncidentQueryService incidents, UrbanContextRepository contexts) {
        this.incidents = incidents;
        this.contexts = contexts;
    }

    /**
     * @throws IncidentNotFoundException if the incident does not exist or the caller may not see it
     */
    public UrbanContextResponse get(UUID incidentId, UUID userId, Role role) {
        incidents.requireVisible(incidentId, userId, role);
        return contexts.findByIncidentId(incidentId)
                .map(UrbanContextResponse::of)
                .orElseGet(UrbanContextResponse::unavailable);
    }
}
