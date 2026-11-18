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
}
