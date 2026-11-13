package es.uma.urbanpulse.incidents.repository;

import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.CategoryCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.DepartmentCount;
import es.uma.urbanpulse.incidents.entity.Incident;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/**
 * The counts of the incidents that match, grouped in the database instead of loading the incidents.
 * Only the groups that have incidents come back; the service adds the empty ones.
 */
public interface IncidentStatisticsRepository {

    /** The count of each status that has incidents. */
    Map<IncidentStatus, Long> countByStatus(Specification<Incident> spec);

    /** The count of each priority that has incidents; the key {@code null} is no priority. */
    Map<Priority, Long> countByPriority(Specification<Incident> spec);

    /** The count of each category that has incidents, {@code null} category included, unordered. */
    List<CategoryCount> countByCategory(Specification<Incident> spec);

    /**
     * The count of each department of an active assignment that has incidents, no active assignment
     * ({@code null} department) included, unordered.
     */
    List<DepartmentCount> countByDepartment(Specification<Incident> spec);
}
