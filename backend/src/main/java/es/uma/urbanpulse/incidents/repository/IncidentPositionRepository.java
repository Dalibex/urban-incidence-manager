package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.dto.IncidentPositionResponse;
import es.uma.urbanpulse.incidents.entity.Incident;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** The positions of incidents for the map, read as a projection instead of whole entities. */
public interface IncidentPositionRepository {

    /**
     * The positions of the incidents that match, the most relevant first: by priority from {@code
     * HIGH} to none, then newest, then by id so the order is stable.
     *
     * @param limit the maximum number of positions to return
     */
    List<IncidentPositionResponse> findPositions(Specification<Incident> spec, int limit);
}
