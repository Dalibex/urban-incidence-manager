package es.uma.urbanpulse.incidents.controller;

import es.uma.urbanpulse.incidents.entity.IncidentStatus;
import es.uma.urbanpulse.incidents.entity.Priority;
import es.uma.urbanpulse.incidents.service.IncidentFilter;
import es.uma.urbanpulse.shared.text.Texts;
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
                    "departmentId",
                    "q");

    /** The filters the contract takes once; the others are arrays and may repeat. */
    static final Set<String> SINGLE_NAMES = Set.of("reportedFrom", "reportedTo", "q");

    // The limits of the text search q in the contract (TextSearch), in characters once trimmed.
    private static final int TEXT_MIN_LENGTH = 3;
    private static final int TEXT_MAX_LENGTH = 100;

    private IncidentFilterParameters() {}

    /**
     * Every argument is {@code null} when its parameter is absent.
     *
     * @throws MalformedRequestException if a list has an empty element, a date is outside the years
     *     1 to 9999, or the text search is too short or too long once trimmed
     */
    static IncidentFilter toFilter(
            List<IncidentStatus> status,
            List<UUID> categoryId,
            List<Priority> priority,
            OffsetDateTime reportedFrom,
            OffsetDateTime reportedTo,
            List<UUID> departmentId,
            String q) {
        return new IncidentFilter(
                asSet("status", status),
                asSet("categoryId", categoryId),
                asSet("priority", priority),
                storable("reportedFrom", reportedFrom),
                storable("reportedTo", reportedTo),
                asSet("departmentId", departmentId),
                searchText(q));
    }

    // The text rule of every other text the backend takes (#322): its white space, also U+00A0, and
    // its length in characters, so an emoji counts as one.
    private static String searchText(String q) {
        if (q == null) {
            return null;
        }
        String text = Texts.strip(q);
        int length = Texts.length(text);
        if (length < TEXT_MIN_LENGTH || length > TEXT_MAX_LENGTH) {
            throw new MalformedRequestException(
                    "The parameter q must have "
                            + TEXT_MIN_LENGTH
                            + " to "
                            + TEXT_MAX_LENGTH
                            + " characters without the spaces around it.");
        }
        // PostgreSQL refuses U+0000 in a text, so it would end in a 500.
        if (Texts.hasNullCharacter(text)) {
            throw new MalformedRequestException(
                    "The parameter q cannot contain the null character.");
        }
        return text;
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
