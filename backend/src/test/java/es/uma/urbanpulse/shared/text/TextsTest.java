package es.uma.urbanpulse.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The text rule of the contract: white space of ECMA-262, the null character and code points. */
class TextsTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n\r", "   ", " ", "　", "﻿"})
    void aTextOfWhiteSpaceOrNoBreakSpacesIsBlank(String text) {
        assertThat(Texts.isBlank(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", " a ", "😀", "\u0000"})
    void aTextWithAVisibleCharacterIsNotBlank(String text) {
        assertThat(Texts.isBlank(text)).isFalse();
    }

    @Test
    void nullIsBlank() {
        assertThat(Texts.isBlank(null)).isTrue();
    }

    @Test
    void theNullCharacterIsFoundAnywhere() {
        assertThat(Texts.hasNullCharacter("Pablo\u0000")).isTrue();
        assertThat(Texts.hasNullCharacter("\u0000")).isTrue();
        assertThat(Texts.hasNullCharacter("Pablo")).isFalse();
    }

    @Test
    void anEmojiCountsAsOneCharacter() {
        assertThat(Texts.length("😀😀")).isEqualTo(2);
        assertThat(Texts.length("ñandú")).isEqualTo(5);
    }

    @Test
    void stripRemovesOnlyTheWhiteSpaceOfTheContract() {
        assertThat(Texts.strip(" \u00A0Ana\u3000\t")).isEqualTo("Ana");
        // String.strip would remove these, but the \S of the contract counts them as visible.
        assertThat(Texts.strip("\u001F\u001F")).isEqualTo("\u001F\u001F");
        assertThat(Texts.strip("\u00A0\u00A0")).isEmpty();
        assertThat(Texts.strip("\uD83D\uDE00")).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void collapseWhiteSpaceTurnsEachRunIntoOneSpace() {
        assertThat(Texts.collapseWhiteSpace("Calle Larios\r\n5,\t29005 \u00A0M\u00E1laga"))
                .isEqualTo("Calle Larios 5, 29005 M\u00E1laga");
        assertThat(Texts.collapseWhiteSpace(" a ")).isEqualTo(" a ");
        // Not white space for the contract: kept, so the caller can refuse it.
        assertThat(Texts.collapseWhiteSpace("a\u0007b")).isEqualTo("a\u0007b");
        assertThat(Texts.collapseWhiteSpace("\uD83D\uDE00  \uD83D\uDE00"))
                .isEqualTo("\uD83D\uDE00 \uD83D\uDE00");
    }
}
