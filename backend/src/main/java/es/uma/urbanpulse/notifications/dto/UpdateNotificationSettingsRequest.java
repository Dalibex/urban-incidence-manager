package es.uma.urbanpulse.notifications.dto;

/**
 * Body of {@code updateNotificationSettings}: an absent property, or {@code null}, keeps its value.
 * At least one is needed ({@code minProperties: 1} in the contract).
 */
public record UpdateNotificationSettingsRequest(Boolean push, Boolean email) {}
