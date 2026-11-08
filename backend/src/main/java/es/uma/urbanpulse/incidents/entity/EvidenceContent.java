package es.uma.urbanpulse.incidents.entity;

import es.uma.urbanpulse.shared.error.FieldError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * The bytes of an evidence photo, with the type detected from its content (magic bytes) and its
 * SHA-256, the {@code content_hash} of {@link Attachment} (ADR-0013, Evidence Policy). Only JPEG,
 * PNG and WebP are accepted; the file name and the declared {@code Content-Type} are never used.
 */
public final class EvidenceContent {

    private static final String FILE_FIELD = "file";

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    // A WebP file is a RIFF container ("RIFF", 4 bytes of size, "WEBP"); other RIFF files, such as
    // WAV or AVI, share the first four bytes.
    private static final byte[] RIFF_MAGIC = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP_FORM = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static final int WEBP_FORM_OFFSET = 8;

    private final byte[] bytes;
    private final String contentType;
    private final String sha256;

    private EvidenceContent(byte[] bytes, String contentType, String sha256) {
        this.bytes = bytes;
        this.contentType = contentType;
        this.sha256 = sha256;
    }

    /**
     * Detects the type and computes the hash.
     *
     * @throws InvalidIncidentDataException on the field {@code file} if the content is empty or is
     *     not a JPEG, PNG or WebP image
     */
    public static EvidenceContent of(byte[] bytes) {
        var copy = bytes.clone();
        if (copy.length == 0) {
            throw invalidFile("The file is empty.");
        }
        var contentType = detectType(copy);
        if (contentType == null) {
            throw invalidFile("The file must be a JPEG, PNG or WebP image.");
        }
        return new EvidenceContent(copy, contentType, sha256Of(copy));
    }

    /** The detected type: {@code image/jpeg}, {@code image/png} or {@code image/webp}. */
    public String contentType() {
        return contentType;
    }

    /** The SHA-256 of the bytes, 64 lowercase hexadecimal characters. */
    public String sha256() {
        return sha256;
    }

    /** The size in bytes. */
    public long size() {
        return bytes.length;
    }

    /** A copy of the bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The type of the content, or {@code null} if it is not an accepted image. */
    private static String detectType(byte[] bytes) {
        if (startsWith(bytes, JPEG_MAGIC, 0)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, PNG_MAGIC, 0)) {
            return "image/png";
        }
        if (startsWith(bytes, RIFF_MAGIC, 0) && startsWith(bytes, WEBP_FORM, WEBP_FORM_OFFSET)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] magic, int offset) {
        return bytes.length >= offset + magic.length
                && Arrays.equals(bytes, offset, offset + magic.length, magic, 0, magic.length);
    }

    private static String sha256Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (MessageDigest specification).
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static InvalidIncidentDataException invalidFile(String message) {
        return new InvalidIncidentDataException(List.of(new FieldError(FILE_FIELD, message)));
    }
}
