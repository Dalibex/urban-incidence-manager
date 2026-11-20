package es.uma.urbanpulse.incidents.service;

import es.uma.urbanpulse.incidents.dto.CreateIncidentRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The SHA-256 of the data of a report, which tells a retry from a different report sent with the
 * same {@code Idempotency-Key} (#289). It is computed from the parsed request, so the formatting of
 * the JSON and the order of its properties do not change it. Each value is written with its length
 * first, so two different reports never give the same text, and an absent value differs from any
 * text.
 */
final class ReportFingerprint {

    private static final String ABSENT = "-;";

    private ReportFingerprint() {}

    /** The hash in hexadecimal: 64 characters. */
    static String of(CreateIncidentRequest request) {
        var location = request.location();
        var text = new StringBuilder();
        append(text, request.title());
        append(text, request.description());
        append(text, Objects.toString(request.categoryId(), null));
        append(text, Objects.toString(location.latitude(), null));
        append(text, Objects.toString(location.longitude(), null));
        append(text, Objects.toString(location.accuracy(), null));
        return HexFormat.of().formatHex(sha256(text.toString()));
    }

    private static void append(StringBuilder text, String value) {
        if (value == null) {
            text.append(ABSENT);
            return;
        }
        text.append(value.length()).append(':').append(value).append(';');
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (MessageDigest's Javadoc).
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
