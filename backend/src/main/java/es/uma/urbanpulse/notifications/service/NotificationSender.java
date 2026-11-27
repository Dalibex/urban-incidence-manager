package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the notifications of one channel to its provider. It is called outside any database
 * transaction and writes nothing: the delivery job stores the outcomes.
 */
public interface NotificationSender {

    NotificationChannel channel();

    /**
     * @return the outcome of each notification, by id; a notification left out counts as {@code
     *     UNAVAILABLE}
     */
    Map<UUID, DeliveryOutcome> send(List<OutgoingNotification> batch);
}
