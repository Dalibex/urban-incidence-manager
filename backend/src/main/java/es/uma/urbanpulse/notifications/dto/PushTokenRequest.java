package es.uma.urbanpulse.notifications.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code createPushToken} and {@code createPushTokenDeletion}: an Expo push token. */
public record PushTokenRequest(
        @NotNull @Size(max = 255) @Pattern(regexp = "^Expo(nent)?PushToken\\[[A-Za-z0-9_-]+\\]$")
                String token) {}
