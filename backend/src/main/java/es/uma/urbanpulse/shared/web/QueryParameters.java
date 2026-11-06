package es.uma.urbanpulse.shared.web;

import java.util.Set;
import org.springframework.util.MultiValueMap;

/**
 * Checks the query of a listing against the parameters its contract documents. Spring ignores a
 * parameter no method argument asks for, so a mistyped filter ({@code ?categoryID=...}) would
 * return the unfiltered list as if it had worked; and it turns an empty value ({@code
 * ?categoryId=}) into {@code null}, which would end in a silent default or a {@code 500}.
 */
public final class QueryParameters {

    private QueryParameters() {}

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
