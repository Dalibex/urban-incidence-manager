package es.uma.urbanpulse.incidents.dto;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.util.List;
import java.util.UUID;

/**
 * {@code IncidentStatisticsResponse}: the counts of the staff dashboard. Each list adds up to
 * {@code total}.
 *
 * @param total every incident that matches, with no cap
 */
public record IncidentStatisticsResponse(
        long total,
        List<StatusCount> byStatus,
        List<PriorityCount> byPriority,
        List<CategoryCount> byCategory,
        List<DepartmentCount> byDepartment) {

    public IncidentStatisticsResponse {
        byStatus = List.copyOf(byStatus);
        byPriority = List.copyOf(byPriority);
        byCategory = List.copyOf(byCategory);
        byDepartment = List.copyOf(byDepartment);
    }

    /** {@code StatusCount}. */
    public record StatusCount(IncidentStatus status, long count) {}

    /**
     * {@code PriorityCount}.
     *
     * @param priority {@code null} for the incidents without priority
     */
    public record PriorityCount(Priority priority, long count) {}

    /**
     * {@code CategoryCount}.
     *
     * @param categoryId {@code null} for the incidents without category, together with {@code name}
     */
    public record CategoryCount(UUID categoryId, String name, long count) {}

    /**
     * {@code DepartmentCount}.
     *
     * @param departmentId {@code null} for the incidents without an active assignment, together
     *     with {@code name}
     */
    public record DepartmentCount(UUID departmentId, String name, long count) {}
}
