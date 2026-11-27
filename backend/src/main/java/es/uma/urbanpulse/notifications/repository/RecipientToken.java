package es.uma.urbanpulse.notifications.repository;

import java.util.UUID;

/** A push token and the user who registered it. */
public record RecipientToken(UUID userId, String token) {}
