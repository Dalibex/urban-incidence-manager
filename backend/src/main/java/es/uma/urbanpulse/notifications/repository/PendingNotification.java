package es.uma.urbanpulse.notifications.repository;

import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import java.util.UUID;

/** A {@code PENDING} notification that is due, with what the delivery job needs to send it. */
public record PendingNotification(
        UUID id,
        UUID incidentId,
        UUID recipientId,
        String recipientEmail,
        NotificationChannel channel,
        short attempts,
        String message) {}
