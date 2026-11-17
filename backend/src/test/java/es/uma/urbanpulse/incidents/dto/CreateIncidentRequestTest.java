package es.uma.urbanpulse.incidents.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean validation of the report body (RF03): what a broken form sends must be refused with one
 * entry per invalid field, as the contract answers with {@code 422} and {@code errors}. Nested
 * coordinates reject as {@code location.latitude} and {@code location.longitude}.
 */
class CreateIncidentRequestTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validRequestHasNoViolations() {
        assertThat(
                        validator.validate(
                                request(
                                        VALID_TITLE,
                                        VALID_DESCRIPTION,
                                        location(36.7213, -4.4214, 12.5))))
                .isEmpty();
    }

    @Test
    void aBlankOrNullTitleIsRejected() {
        for (String title : new String[] {null, "", "   "}) {
            assertThat(paths(request(title, VALID_DESCRIPTION, location(36.7213, -4.4214, 12.5))))
                    .containsExactly("title");
        }
    }

    @Test
    void aTitleOfOneHundredTwentyOneCharactersIsRejected() {
        var title = "x".repeat(121);
        assertThat(paths(request(title, VALID_DESCRIPTION, location(36.7213, -4.4214, 12.5))))
                .containsExactly("title");
    }

    @Test
    void aBlankOrNullDescriptionIsRejected() {
        for (String description : new String[] {null, "", "   "}) {
            assertThat(paths(request(VALID_TITLE, description, location(36.7213, -4.4214, 12.5))))
                    .containsExactly("description");
        }
    }

    @Test
    void aDescriptionOfTwoThousandOneCharactersIsRejected() {
        var description = "x".repeat(2001);
        assertThat(paths(request(VALID_TITLE, description, location(36.7213, -4.4214, 12.5))))
                .containsExactly("description");
    }

    // #285: no-break spaces look empty, as the \S of the contract says.
    @Test
    void aTitleOrDescriptionOfNoBreakSpacesIsRejected() {
        var spaces = "\u00A0\u00A0\u00A0";
        assertThat(paths(request(spaces, spaces, location(36.7213, -4.4214, 12.5))))
                .containsExactlyInAnyOrder("title", "description");
    }

    // #285: PostgreSQL cannot store U+0000.
    @Test
    void aTitleOrDescriptionWithTheNullCharacterIsRejected() {
        assertThat(
                        paths(
                                request(
                                        "Street\u0000light",
                                        "Off\u0000",
                                        location(36.7213, -4.4214, 12.5))))
                .containsExactlyInAnyOrder("title", "description");
    }

    // #285: the limits count characters, as maxLength does, so an emoji is one.
    @Test
    void theLimitsCountEmojiAsOneCharacter() {
        var emoji = "\uD83D\uDE00";
        var at = location(36.7213, -4.4214, 12.5);
        assertThat(validator.validate(request(emoji.repeat(120), emoji.repeat(2000), at)))
                .isEmpty();
        assertThat(paths(request(emoji.repeat(121), emoji.repeat(2001), at)))
                .containsExactlyInAnyOrder("title", "description");
    }

    @Test
    void lettersBetweenNoBreakSpacesAreAccepted() {
        assertThat(
                        validator.validate(
                                request(
                                        "\u00A0Street light off\u00A0",
                                        VALID_DESCRIPTION,
                                        location(36.7213, -4.4214, 12.5))))
                .isEmpty();
    }

    @Test
    void aMissingLocationAndMissingCoordinatesAreRejected() {
        assertThat(paths(request(VALID_TITLE, VALID_DESCRIPTION, null)))
                .containsExactly("location");
        assertThat(paths(request(VALID_TITLE, VALID_DESCRIPTION, location(null, -4.4214, 12.5))))
                .containsExactly("location.latitude");
        assertThat(paths(request(VALID_TITLE, VALID_DESCRIPTION, location(36.7213, null, 12.5))))
                .containsExactly("location.longitude");
    }

    @Test
    void anOutOfRangeLatitudeIsRejected() {
        for (double latitude : new double[] {90.5, -90.5}) {
            assertThat(
                            paths(
                                    request(
                                            VALID_TITLE,
                                            VALID_DESCRIPTION,
                                            location(latitude, -4.4214, 12.5))))
                    .containsExactly("location.latitude");
        }
    }

    @Test
    void anOutOfRangeLongitudeIsRejected() {
        for (double longitude : new double[] {180.5, -180.5}) {
            assertThat(
                            paths(
                                    request(
                                            VALID_TITLE,
                                            VALID_DESCRIPTION,
                                            location(36.7213, longitude, 12.5))))
                    .containsExactly("location.longitude");
        }
    }

    @Test
    void everyInvalidFieldAtOnceIsReportedTogether() {
        assertThat(paths(request("   ", "   ", location(91.0, -181.0, null))))
                .containsExactlyInAnyOrder(
                        "title", "description", "location.latitude", "location.longitude");
    }

    @Test
    void aNegativeAccuracyIsRejectedButAMissingOneIsNot() {
        assertThat(paths(request(VALID_TITLE, VALID_DESCRIPTION, location(36.7213, -4.4214, -0.5))))
                .containsExactly("location.accuracy");
        assertThat(
                        validator.validate(
                                request(
                                        VALID_TITLE,
                                        VALID_DESCRIPTION,
                                        location(36.7213, -4.4214, null))))
                .isEmpty();
    }

    private static final String VALID_TITLE = "Street light off";
    private static final String VALID_DESCRIPTION =
            "The street light in front of number 12 has been off for three days.";

    private static CreateIncidentRequest.ReportedLocation location(
            Double latitude, Double longitude, Double accuracy) {
        return new CreateIncidentRequest.ReportedLocation(latitude, longitude, accuracy);
    }

    private static CreateIncidentRequest request(
            String title, String description, CreateIncidentRequest.ReportedLocation location) {
        return new CreateIncidentRequest(title, description, UUID.randomUUID(), location);
    }

    private static Set<String> paths(CreateIncidentRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
