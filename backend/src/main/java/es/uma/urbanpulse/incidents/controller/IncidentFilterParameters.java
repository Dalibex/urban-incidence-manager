package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.IncidentFilter;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The filter parameters shared by {@code listIncidents} and {@code listIncidentLocations} in the
 * contract, turned into an {@link IncidentFilter}.
 */
final class IncidentFilterParameters {

    /** The query parameters of the filters, to accept them next to each operation's own. */
    static final Set<String> NAMES =
            Set.of(
                    "status",
                    "categoryId",
                    "priority",
                    "reportedFrom",
                    "reportedTo",
                    "departmentId");

    private IncidentFilterParameters() {}

    /** Every argument is {@code null} when its parameter is absent. */
    static IncidentFilter toFilter(
            List<IncidentStatus> status,
            List<UUID> categoryId,
            List<Priority> priority,
            OffsetDateTime reportedFrom,
            OffsetDateTime reportedTo,
            List<UUID> departmentId) {
        return new IncidentFilter(
                asSet(status),
                asSet(categoryId),
                asSet(priority),
                reportedFrom == null ? null : reportedFrom.toInstant(),
                reportedTo == null ? null : reportedTo.toInstant(),
                asSet(departmentId));
    }

    // An absent repeatable parameter arrives as null.
    private static <T> Set<T> asSet(Collection<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
