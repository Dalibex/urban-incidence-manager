package es.uma.urbanpulse.shared.text;

/**
 * The text rule of the contract, for the checks that cannot be Bean Validation annotations because
 * they run after the role and the state (ADR-0020). {@link Text} applies the same rule to a
 * request.
 */
public final class Texts {

    private Texts() {}

    /**
     * Whether the text has no character that the {@code pattern: "\\S"} of the contract accepts.
     * JSON Schema patterns use the {@code \s} of ECMA-262, which also counts no-break spaces such
     * as U+00A0 and U+FEFF; {@link String#isBlank} does not.
     */
    public static boolean onlyWhiteSpace(String text) {
        return text.codePoints().allMatch(Texts::isWhiteSpace);
    }

    /** {@code null}, or a text with no visible character ({@link #onlyWhiteSpace}). */
    public static boolean isBlank(String text) {
        return text == null || onlyWhiteSpace(text);
    }

    /** Whether the text holds U+0000, which PostgreSQL refuses to store in a text column. */
    public static boolean hasNullCharacter(String text) {
        return text.indexOf('\0') >= 0;
    }

    /**
     * The length in characters, as JSON Schema counts {@code minLength} and {@code maxLength}: an
     * emoji is one character, not two UTF-16 units.
     */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * The text without the white space of the contract at both ends. {@link String#strip} also
     * removes U+001C to U+001F, which the {@code \S} of the contract counts as visible, so a name
     * that passes {@link Text} could be stored empty.
     */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isWhiteSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isWhiteSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    /**
     * The text with each run of the white space of the contract, a line break or a tab included,
     * turned into one space: for a search text, where a pasted line break means a space.
     */
    public static String collapseWhiteSpace(String text) {
        var collapsed = new StringBuilder(text.length());
        var inWhiteSpace = false;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (isWhiteSpace(c)) {
                inWhiteSpace = true;
                continue;
            }
            if (inWhiteSpace) {
                collapsed.append(' ');
                inWhiteSpace = false;
            }
            collapsed.appendCodePoint(c);
        }
        if (inWhiteSpace) {
            collapsed.append(' ');
        }
        return collapsed.toString();
    }

    private static boolean isWhiteSpace(int c) {
        return Character.getType(c) == Character.SPACE_SEPARATOR
                || (c >= '\t' && c <= '\r')
                || c == 0x2028
                || c == 0x2029
                || c == 0xFEFF;
    }
}
