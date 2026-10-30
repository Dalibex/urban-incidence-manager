package es.uma.urbanpulse.identity.dto;

import java.time.Instant;

/** Token pair of a session (ADR-0004), as {@code SessionResponse} in the contract. */
public record SessionResponse(
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt) {

    private static final String BEARER = "Bearer";

    public SessionResponse(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt) {
        this(BEARER, accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt);
    }

    /** Keeps both tokens out of logs: whoever reads them can use them. */
    @Override
    public String toString() {
        return "SessionResponse[accessTokenExpiresAt="
                + accessTokenExpiresAt
                + ", refreshTokenExpiresAt="
                + refreshTokenExpiresAt
                + "]";
    }
}
