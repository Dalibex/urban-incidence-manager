package es.uma.urbanpulse.incidents.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The rules of an internal comment (#50): it is never visible to the citizen, and its text. */
class CommentTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 11, 7, 12, 5, 0, 0, ZoneOffset.UTC);

    private final IncidentFixture fixture = new IncidentFixture();
    private final Incident incident = fixture.reported();

    @Test
    void isInternalAndKeepsItsData() {
        var comment =
                Comment.internal(
                        incident, fixture.operator, "The access is through the back street.", NOW);

        assertThat(comment.getVisibleToCitizen()).isFalse();
        assertThat(comment.getIncident()).isSameAs(incident);
        assertThat(comment.getAuthor()).isSameAs(fixture.operator);
        assertThat(comment.getText()).isEqualTo("The access is through the back street.");
        assertThat(comment.getCreatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t"})
    void refusesAMissingOrBlankText(String text) {
        assertRefused(text, "Write the comment.");
    }

    @Test
    void acceptsTheLongestTextCountingCharactersNotUtf16Units() {
        // Each emoji is one character but two UTF-16 units: 2,000 of them are 4,000 units.
        var longest = "🚧".repeat(Comment.MAX_TEXT_LENGTH);

        assertThat(Comment.internal(incident, fixture.technician, longest, NOW).getText())
                .isEqualTo(longest);
    }

    @Test
    void refusesATextOverTheLimit() {
        assertRefused(
                "a".repeat(Comment.MAX_TEXT_LENGTH + 1),
                "The comment can have at most 2000 characters.");
    }

    private void assertRefused(String text, String message) {
        assertThatThrownBy(() -> Comment.internal(incident, fixture.operator, text, NOW))
                .isInstanceOf(InvalidIncidentDataException.class)
                .extracting(error -> ((InvalidDataException) error).fieldErrors())
                .isEqualTo(List.of(new FieldError("text", message)));
    }
}
