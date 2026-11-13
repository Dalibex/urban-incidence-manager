package es.uma.urbanpulse.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.CategoryCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.DepartmentCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.PriorityCount;
import es.uma.urbanpulse.incidents.dto.IncidentStatisticsResponse.StatusCount;
import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The zero-filling and the order of the statistics, which do not need the database. */
class IncidentStatisticsAssemblerTest {

    private static final IncidentFilter NO_FILTER =
            new IncidentFilter(Set.of(), Set.of(), Set.of(), null, null, Set.of());

    @Test
    void withoutAStatusFilterEveryStatusIsListedInDeclaredOrderWithRealZeros() {
        var response =
                IncidentStatisticsAssembler.assemble(
                        NO_FILTER,
                        Map.of(IncidentStatus.CLOSED, 3L, IncidentStatus.VALIDATED, 2L),
                        noPriorities(),
                        List.of(),
                        List.of());

        assertThat(response.total()).isEqualTo(5);
        assertThat(response.byStatus())
                .extracting(StatusCount::status)
                .containsExactly(IncidentStatus.values());
        assertThat(response.byStatus())
                .extracting(StatusCount::count)
                .containsExactly(0L, 2L, 0L, 0L, 0L, 0L, 0L, 3L);
    }

    @Test
    void aStatusFilterListsOnlyItsStatusesInDeclaredOrder() {
        var filter =
                new IncidentFilter(
                        Set.of(IncidentStatus.VALIDATED, IncidentStatus.REPORTED),
                        Set.of(),
                        Set.of(),
                        null,
                        null,
                        Set.of());

        var response =
                IncidentStatisticsAssembler.assemble(
                        filter,
                        Map.of(IncidentStatus.VALIDATED, 2L),
                        noPriorities(),
                        List.of(),
                        List.of());

        assertThat(response.byStatus())
                .containsExactly(
                        new StatusCount(IncidentStatus.REPORTED, 0),
                        new StatusCount(IncidentStatus.VALIDATED, 2));
    }

    @Test
    void prioritiesGoFromHighToNoneWithRealZeros() {
        var counts = new HashMap<Priority, Long>();
        counts.put(Priority.LOW, 4L);
        counts.put(null, 1L);

        var response =
                IncidentStatisticsAssembler.assemble(
                        NO_FILTER, Map.of(), counts, List.of(), List.of());

        assertThat(response.byPriority())
                .containsExactly(
                        new PriorityCount(Priority.HIGH, 0),
                        new PriorityCount(Priority.MEDIUM, 0),
                        new PriorityCount(Priority.LOW, 4),
                        new PriorityCount(null, 1));
    }

    @Test
    void aPriorityFilterListsOnlyItsPrioritiesAndNotTheGroupWithoutPriority() {
        var filter =
                new IncidentFilter(
                        Set.of(),
                        Set.of(),
                        Set.of(Priority.LOW, Priority.HIGH),
                        null,
                        null,
                        Set.of());

        var response =
                IncidentStatisticsAssembler.assemble(
                        filter, Map.of(), Map.of(Priority.LOW, 2L), List.of(), List.of());

        assertThat(response.byPriority())
                .containsExactly(
                        new PriorityCount(Priority.HIGH, 0), new PriorityCount(Priority.LOW, 2));
    }

    @Test
    void categoriesGoLargestFirstThenByNameAndTheGroupWithoutCategoryLast() {
        var alpha = new CategoryCount(UUID.randomUUID(), "Alpha", 2);
        var bravo = new CategoryCount(UUID.randomUUID(), "Bravo", 2);
        var large = new CategoryCount(UUID.randomUUID(), "Zulu", 5);
        // The largest group of all, still last because it has no category.
        var none = new CategoryCount(null, null, 9);

        var response =
                IncidentStatisticsAssembler.assemble(
                        NO_FILTER,
                        Map.of(),
                        noPriorities(),
                        List.of(none, bravo, large, alpha),
                        List.of());

        assertThat(response.byCategory()).containsExactly(large, alpha, bravo, none);
    }

    @Test
    void departmentsGoLargestFirstThenByNameAndTheGroupWithoutAssignmentLast() {
        var north = new DepartmentCount(UUID.randomUUID(), "North", 1);
        var south = new DepartmentCount(UUID.randomUUID(), "South", 1);
        var large = new DepartmentCount(UUID.randomUUID(), "West", 3);
        var none = new DepartmentCount(null, null, 7);

        var response =
                IncidentStatisticsAssembler.assemble(
                        NO_FILTER,
                        Map.of(),
                        noPriorities(),
                        List.of(),
                        List.of(none, south, large, north));

        assertThat(response.byDepartment()).containsExactly(large, north, south, none);
    }

    // A HashMap, like the repository returns: an immutable map rejects the null key.
    private static Map<Priority, Long> noPriorities() {
        return new HashMap<>();
    }
}
