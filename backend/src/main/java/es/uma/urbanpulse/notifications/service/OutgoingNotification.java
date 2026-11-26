package es.uma.urbanpulse.notifications.service;

import java.util.List;
import java.util.UUID;

/**
 * A notification on its way to a provider.
 *
 * @param pushTokens the devices of the recipient at send time; empty for an email
 */
public record OutgoingNotification(
        UUID id, UUID incidentId, String message, String email, List<String> pushTokens) {

    public OutgoingNotification {
        pushTokens = List.copyOf(pushTokens);
    }
}
