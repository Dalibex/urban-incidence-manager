package es.uma.urbanpulse.incidents.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The type of an evidence photo is detected from its magic bytes and its {@code content_hash} is
 * the SHA-256 of the bytes (ADR-0013, Evidence Policy). Covers the content side of #67: T5 (a PDF
 * renamed to {@code .jpg} is refused, whatever its name) and T13 (the hash that identifies a
 * retry).
 */
class EvidenceContentTest {

    private static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] JPEG = withHeader(JPEG_HEADER);
    private static final byte[] PNG =
            withHeader(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    private static final byte[] WEBP =
            withHeader("RIFF$\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));

    @Test
    void detectsAJpegFromItsContent() {
        assertThat(EvidenceContent.of(JPEG).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void detectsAPngFromItsContent() {
        assertThat(EvidenceContent.of(PNG).contentType()).isEqualTo("image/png");
    }

    @Test
    void detectsAWebpFromItsContent() {
        assertThat(EvidenceContent.of(WEBP).contentType()).isEqualTo("image/webp");
    }

    @Test
    void refusesAPdfWhateverItsName() {
        var pdf = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);

        assertRefused(pdf);
    }

    @Test
    void refusesARiffFileThatIsNotWebp() {
        var wav = "RIFF$\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);

        assertRefused(wav);
    }

    @Test
    void refusesContentShorterThanAnyHeader() {
        assertRefused(new byte[] {(byte) 0xFF, (byte) 0xD8});
    }

    @Test
    void refusesAnEmptyFile() {
        assertRefused(new byte[0]);
    }

    @Test
    void theHashIsTheSha256OfTheBytes() {
        var content = withHeader(JPEG_HEADER, "street light");

        // Computed outside the code under test: hashlib.sha256 of the same bytes.
        assertThat(EvidenceContent.of(content).sha256())
                .isEqualTo("3a4f69697890844d2aafa29f5178ecd8e10a4d5c02bbef0c46c4f6826960fa19");
    }

    @Test
    void theSameBytesGiveTheSameHashAndOthersDoNot() {
        assertThat(EvidenceContent.of(JPEG.clone()).sha256())
                .isEqualTo(EvidenceContent.of(JPEG).sha256())
                .isNotEqualTo(EvidenceContent.of(PNG).sha256());
    }

    @Test
    void keepsTheSizeAndACopyOfTheBytes() {
        var content = EvidenceContent.of(PNG);

        assertThat(content.size()).isEqualTo(PNG.length);
        assertThat(content.bytes()).isEqualTo(PNG);
    }

    @Test
    void laterChangesToTheInputDoNotChangeTheContent() {
        var input = JPEG.clone();
        var content = EvidenceContent.of(input);

        input[0] = 0;

        assertThat(content.bytes()).isEqualTo(JPEG);
        assertThat(content.contentType()).isEqualTo("image/jpeg");
    }

    private static void assertRefused(byte[] bytes) {
        assertThatThrownBy(() -> EvidenceContent.of(bytes))
                .isInstanceOfSatisfying(
                        InvalidDataException.class,
                        e ->
                                assertThat(e.fieldErrors())
                                        .extracting(FieldError::field)
                                        .containsExactly("file"));
    }

    private static byte[] withHeader(byte[] header) {
        return withHeader(header, "rest of the image");
    }

    private static byte[] withHeader(byte[] header, String rest) {
        var tail = rest.getBytes(StandardCharsets.US_ASCII);
        var bytes = new byte[header.length + tail.length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(tail, 0, bytes, header.length, tail.length);
        return bytes;
    }
}
