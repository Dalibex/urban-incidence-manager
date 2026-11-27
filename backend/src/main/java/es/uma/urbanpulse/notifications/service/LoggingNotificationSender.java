package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sender of a channel that is off ({@code NOTIFICATIONS_PUSH_ENABLED}, {@code
 * NOTIFICATIONS_EMAIL_ENABLED}): it sends nothing and ends every row {@code DELIVERED}. The log
 * names the channel and the notification, never its text, address or device.
 *
 * <p>Rows still end {@code DELIVERED} because local development and tests simulate the provider:
 * nothing is sent and the log says so.
 */
final class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    private final NotificationChannel channel;

    LoggingNotificationSender(NotificationChannel channel) {
        this.channel = channel;
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public Map<UUID, DeliveryOutcome> send(List<OutgoingNotification> batch) {
        var outcomes = new LinkedHashMap<UUID, DeliveryOutcome>();
        for (var notification : batch) {
            log.info("{} is off: notification {} only logged", channel, notification.id());
            outcomes.put(notification.id(), DeliveryOutcome.delivered());
        }
        return outcomes;
    }
}
