package es.uma.urbanpulse.geospatial.controller;

import es.uma.urbanpulse.geospatial.dto.PlaceResponse;
import es.uma.urbanpulse.geospatial.service.PlaceSearchService;
import es.uma.urbanpulse.shared.security.AuthenticatedUser;
import es.uma.urbanpulse.shared.text.Texts;
import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.QueryParameters;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listPlaces}: the address search of the report form of the mobile app (#179). Only a
 * citizen may call it ({@code ApiAuthorizationRules}).
 */
@RestController
@RequestMapping("/api/v1/places")
class PlaceController {

    private static final String QUERY = "q";
    // The limits of q in the contract, in characters once stripped.
    private static final int QUERY_MIN_LENGTH = 3;
    private static final int QUERY_MAX_LENGTH = 200;

    private final PlaceSearchService places;

    PlaceController(PlaceSearchService places) {
        this.places = places;
    }

    @GetMapping
    List<PlaceResponse> listPlaces(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam MultiValueMap<String, String> parameters) {
        QueryParameters.requireKnownAndFilled(parameters, Set.of(QUERY));
        QueryParameters.requireSingle(parameters, Set.of(QUERY));
        return places.search(query(parameters.getFirst(QUERY)), user.id());
    }

    // The text rule of every other text the backend takes (#322): its white space, also U+00A0,
    // and its length in characters, so an emoji counts as one.
    private static String query(String q) {
        if (q == null) {
            throw new MalformedRequestException("The parameter q is required.");
        }
        var text = Texts.strip(q);
        var length = Texts.length(text);
        if (length < QUERY_MIN_LENGTH || length > QUERY_MAX_LENGTH) {
            throw new MalformedRequestException(
                    "The parameter q must have "
                            + QUERY_MIN_LENGTH
                            + " to "
                            + QUERY_MAX_LENGTH
                            + " characters without the spaces around it.");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new MalformedRequestException(
                    "The parameter q cannot contain control characters.");
        }
        return text;
    }
}
