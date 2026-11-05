package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** The query of a listing checked against its contract (docs/api.md, "Listings"). */
class QueryParametersTest {

    private static final Set<String> ACCEPTED = Set.of("page", "status", "categoryId");

    @Test
    void documentedParametersWithValuesAreAccepted() {
        var query = query(Map.of("page", List.of("1"), "status", List.of("REPORTED", "VALIDATED")));

        assertThatCode(() -> QueryParameters.requireKnownAndFilled(query, ACCEPTED))
                .doesNotThrowAnyException();
    }

    @Test
    void noParametersAreAccepted() {
        assertThatCode(() -> QueryParameters.requireKnownAndFilled(query(Map.of()), ACCEPTED))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownParameterIsABadRequestThatNamesIt() {
        var query = query(Map.of("colour", List.of("red")));

        assertThatThrownBy(() -> QueryParameters.requireKnownAndFilled(query, ACCEPTED))
                .isInstanceOf(MalformedRequestException.class)
                .hasMessageContaining("colour");
    }

    @Test
    void aParameterNameIsCaseSensitive() {
        var query = query(Map.of("categoryID", List.of("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")));

        assertThatThrownBy(() -> QueryParameters.requireKnownAndFilled(query, ACCEPTED))
                .isInstanceOf(MalformedRequestException.class);
    }

    @Test
    void anEmptyValueIsABadRequest() {
        var query = query(Map.of("categoryId", List.of("")));

        assertThatThrownBy(() -> QueryParameters.requireKnownAndFilled(query, ACCEPTED))
                .isInstanceOf(MalformedRequestException.class)
                .hasMessageContaining("categoryId");
    }

    @Test
    void anEmptyValueAmongOthersIsABadRequest() {
        var query = query(Map.of("status", List.of("REPORTED", " ")));

        assertThatThrownBy(() -> QueryParameters.requireKnownAndFilled(query, ACCEPTED))
                .isInstanceOf(MalformedRequestException.class);
    }

    private static MultiValueMap<String, String> query(Map<String, List<String>> values) {
        return new LinkedMultiValueMap<>(values);
    }
}
