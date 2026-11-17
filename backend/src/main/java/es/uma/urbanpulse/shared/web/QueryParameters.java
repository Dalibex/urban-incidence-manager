package es.uma.urbanpulse.shared.web;

import java.time.Instant;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/**
 * Checks the query of a listing against the parameters its contract documents. Spring ignores a
 * parameter no method argument asks for, so a mistyped filter ({@code ?categoryID=...}) would
 * return the unfiltered list as if it had worked; and it turns an empty value ({@code
 * ?categoryId=}) into {@code null}, which would end in a silent default or a {@code 500}.
 */
public final class QueryParameters {

    // PostgreSQL's timestamptz cannot hold every year Java parses (+300000 is valid ISO 8601), and
    // no query of the API is about the years outside these.
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private QueryParameters() {}

    /**
     * @param name the parameter the instant comes from, for the message
     * @param instant {@code null} when the parameter is absent
     * @return {@code instant}, unchanged
     * @throws MalformedRequestException if {@code instant} is outside the years 1 to 9999
     */
    public static Instant requireStorable(String name, Instant instant) {
        if (instant != null && (instant.isBefore(EARLIEST) || instant.isAfter(LATEST))) {
            throw invalidValue(name);
        }
        return instant;
    }

    /** The {@code 400} of a parameter whose value cannot be read. */
    public static MalformedRequestException invalidValue(String name) {
        return new MalformedRequestException("Invalid value of the parameter " + name + ".");
    }

    /**
     * @param parameters every parameter of the request, as {@code @RequestParam MultiValueMap}
     *     receives them
     * @param accepted the parameters the operation documents in the contract
     * @throws MalformedRequestException if a parameter is not one of {@code accepted}, or has an
     *     empty value
     */
    public static void requireKnownAndFilled(
            MultiValueMap<String, String> parameters, Set<String> accepted) {
        for (var parameter : parameters.entrySet()) {
            var name = parameter.getKey();
            if (!accepted.contains(name)) {
                throw new MalformedRequestException(
                        "Unknown parameter "
                                + name
                                + ". The accepted parameters are "
                                + String.join(", ", accepted.stream().sorted().toList())
                                + ".");
            }
            if (parameter.getValue().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new MalformedRequestException("The parameter " + name + " has no value.");
            }
        }
    }
}
