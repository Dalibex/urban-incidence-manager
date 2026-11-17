package es.uma.urbanpulse.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The {@link Text} constraint: the null character, white space and length in characters. */
class TextValidatorTest {

    private static final String EMOJI = "😀";

    private static Validator validator;

    record Name(@Text(max = 3) String value) {}

    record Password(@Text(min = 4, visible = false) String value) {}

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void aMissingValueIsLeftToNotNull() {
        assertThat(validator.validate(new Name(null))).isEmpty();
    }

    @Test
    void theNullCharacterIsRefused() {
        assertThat(messages(new Name("a\u0000")))
                .containsExactly("must not contain the null character");
        assertThat(messages(new Password("abcd\u0000")))
                .containsExactly("must not contain the null character");
    }

    @Test
    void aTextOfNoBreakSpacesIsBlank() {
        assertThat(messages(new Name("   "))).containsExactly("must not be blank");
        assertThat(messages(new Name(""))).containsExactly("must not be blank");
    }

    @Test
    void lettersBetweenNoBreakSpacesAreAccepted() {
        assertThat(validator.validate(new Name(" a "))).isEmpty();
    }

    @Test
    void theMaximumCountsEmojiAsOneCharacter() {
        assertThat(validator.validate(new Name(EMOJI.repeat(3)))).isEmpty();
        assertThat(messages(new Name(EMOJI.repeat(4))))
                .containsExactly("must be at most 3 characters");
    }

    @Test
    void theMinimumCountsEmojiAsOneCharacter() {
        assertThat(messages(new Password(EMOJI.repeat(3))))
                .containsExactly("must be at least 4 characters");
        assertThat(validator.validate(new Password(EMOJI.repeat(4)))).isEmpty();
    }

    @Test
    void aTextThatNeedsNoVisibleCharacterMayBeWhiteSpace() {
        assertThat(validator.validate(new Password("    "))).isEmpty();
    }

    private static java.util.List<String> messages(Object bean) {
        return validator.validate(bean).stream().map(ConstraintViolation::getMessage).toList();
    }
}
