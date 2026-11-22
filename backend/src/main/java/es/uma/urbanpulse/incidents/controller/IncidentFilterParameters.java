package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.IncidentFilter;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.QueryParameters;
import java.time.Instant;
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

    /** The filters the contract takes once; the others are arrays and may repeat. */
    static final Set<String> SINGLE_NAMES = Set.of("reportedFrom", "reportedTo");

    private IncidentFilterParameters() {}

    /**
     * Every argument is {@code null} when its parameter is absent.
     *
     * @throws MalformedRequestException if a list has an empty element, or a date is outside the
     *     years 1 to 9999
     */
    static IncidentFilter toFilter(
            List<IncidentStatus> status,
            List<UUID> categoryId,
            List<Priority> priority,
            OffsetDateTime reportedFrom,
            OffsetDateTime reportedTo,
            List<UUID> departmentId) {
        return new IncidentFilter(
                asSet("status", status),
                asSet("categoryId", categoryId),
                asSet("priority", priority),
                storable("reportedFrom", reportedFrom),
                storable("reportedTo", reportedTo),
                asSet("departmentId", departmentId));
    }

    // An absent repeatable parameter arrives as null. Spring splits a value on commas and turns an
    // empty piece (?status=REPORTED, or ?categoryId=a,,b) into a null element.
    private static <T> Set<T> asSet(String name, Collection<T> values) {
        if (values == null) {
            return Set.of();
        }
        if (values.contains(null)) {
            throw new MalformedRequestException("The parameter " + name + " has an empty value.");
        }
        return Set.copyOf(values);
    }

    private static Instant storable(String name, OffsetDateTime value) {
        return QueryParameters.requireStorable(name, value == null ? null : value.toInstant());
    }
}
