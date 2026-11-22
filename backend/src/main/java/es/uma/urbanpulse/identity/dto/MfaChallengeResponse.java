package es.uma.urbanpulse.identity.dto;

import java.time.Instant;

/** The first step of a sign-in that needs the second factor, as in the contract. No tokens yet. */
public record MfaChallengeResponse(
        String challengeToken, Instant challengeExpiresAt, boolean enrollmentRequired)
        implements SignInResponse {

    /** Keeps the challenge token out of logs: with it and a code, anyone signs in. */
    @Override
    public String toString() {
        return "MfaChallengeResponse[challengeExpiresAt="
                + challengeExpiresAt
                + ", enrollmentRequired="
                + enrollmentRequired
                + "]";
    }
}
