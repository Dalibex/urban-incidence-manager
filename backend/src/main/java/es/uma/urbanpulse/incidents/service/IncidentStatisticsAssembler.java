package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.CategoryCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.DepartmentCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.PriorityCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.StatusCount;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the counts of the database, which only hold the groups that have incidents, into the
 * response of {@code getIncidentStatistics}: the status and priority the filter allows are listed
 * with a real {@code 0}, and every list has the order of the contract.
 */
final class IncidentStatisticsAssembler {

    // The contract lists the priorities from the most urgent, the opposite of the enum.
    private static final List<Priority> PRIORITIES_BY_URGENCY =
            List.of(Priority.HIGH, Priority.MEDIUM, Priority.LOW);

    private IncidentStatisticsAssembler() {}

    static IncidentStatisticsResponse assemble(
            IncidentFilter filter,
            Map<IncidentStatus, Long> statuses,
            Map<Priority, Long> priorities,
            List<CategoryCount> categories,
            List<DepartmentCount> departments) {
        var total = statuses.values().stream().mapToLong(Long::longValue).sum();
        return new IncidentStatisticsResponse(
                total,
                byStatus(filter.statuses(), statuses),
                byPriority(filter.priorities(), priorities),
                sortedCategories(categories),
                sortedDepartments(departments));
    }

    // An empty set means no filter: every status is allowed.
    private static List<StatusCount> byStatus(
            Set<IncidentStatus> allowed, Map<IncidentStatus, Long> counts) {
        return Arrays.stream(IncidentStatus.values())
                .filter(status -> allowed.isEmpty() || allowed.contains(status))
                .map(status -> new StatusCount(status, counts.getOrDefault(status, 0L)))
                .toList();
    }

    // With a priority filter the group without priority is not allowed, so it is not listed.
    private static List<PriorityCount> byPriority(
            Set<Priority> allowed, Map<Priority, Long> counts) {
        var result = new ArrayList<PriorityCount>();
        for (var priority : PRIORITIES_BY_URGENCY) {
            if (allowed.isEmpty() || allowed.contains(priority)) {
                result.add(new PriorityCount(priority, counts.getOrDefault(priority, 0L)));
            }
        }
        if (allowed.isEmpty()) {
            result.add(new PriorityCount(null, counts.getOrDefault(null, 0L)));
        }
        return result;
    }

    // The largest first, then by name, and the group without category last. The id only makes the
    // order stable if two categories ever share a name.
    private static List<CategoryCount> sortedCategories(List<CategoryCount> counts) {
        return counts.stream()
                .sorted(
                        Comparator.comparing((CategoryCount count) -> count.categoryId() == null)
                                .thenComparing(CategoryCount::count, Comparator.reverseOrder())
                                .thenComparing(
                                        CategoryCount::name,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(
                                        CategoryCount::categoryId,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static List<DepartmentCount> sortedDepartments(List<DepartmentCount> counts) {
        return counts.stream()
                .sorted(
                        Comparator.comparing(
                                        (DepartmentCount count) -> count.departmentId() == null)
                                .thenComparing(DepartmentCount::count, Comparator.reverseOrder())
                                .thenComparing(
                                        DepartmentCount::name,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(
                                        DepartmentCount::departmentId,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
