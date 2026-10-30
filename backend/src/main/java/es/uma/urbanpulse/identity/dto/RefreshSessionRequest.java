package es.uma.urbanpulse.identity.dto;

import jakarta.validation.constraints.NotEmpty;

/** Body of {@code refreshSession}. */
public record RefreshSessionRequest(@NotEmpty String refreshToken) {

    @Override
    public String toString() {
        return "RefreshSessionRequest[refreshToken=***]";
    }
}
