package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import es.uma.urbanpulse.notifications.repository.NotificationRepository;
import es.uma.urbanpulse.notifications.repository.PendingNotification;
import es.uma.urbanpulse.notifications.repository.PushTokenRepository;
import es.uma.urbanpulse.notifications.repository.RecipientToken;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the {@code PENDING} notifications after the status change that created them committed
 * (ADR-0031). A notification that waits survives a restart, because it is a row; the next run takes
 * it once it is due.
 *
 * <p>No call to a provider happens inside a database transaction: the rows are read, each channel's
 * sender is called, and then each outcome is written in its own short transaction ({@link
 * NotificationDeliveryWriter}). A channel that is off goes to the {@link
 * LoggingNotificationSender}.
 */
@Service
public class NotificationDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationProperties properties;
    private final NotificationRepository notifications;
    private final PushTokenRepository pushTokens;
    private final NotificationDeliveryWriter writer;
    private final Map<NotificationChannel, NotificationSender> senders =
            new EnumMap<>(NotificationChannel.class);
    private final Clock clock;

    public NotificationDeliveryWorker(
            NotificationProperties properties,
            List<NotificationSender> providers,
            NotificationRepository notifications,
            PushTokenRepository pushTokens,
            NotificationDeliveryWriter writer,
            Clock clock) {
        this.properties = properties;
        this.notifications = notifications;
        this.pushTokens = pushTokens;
        this.writer = writer;
        this.clock = clock;
        for (var channel : NotificationChannel.values()) {
            senders.put(channel, new LoggingNotificationSender(channel));
        }
        for (var provider : providers) {
            if (properties.sends(provider.channel())) {
                senders.put(provider.channel(), provider);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${urbanpulse.notifications.interval}",
            fixedDelayString = "${urbanpulse.notifications.interval}")
    void scheduledRun() {
        if (properties.enabled()) {
            deliverPending();
        }
    }

    /** One run of the worker. Public for the tests, which call it instead of waiting. */
    public void deliverPending() {
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        notifications.findDue(now, Limit.of(properties.batchSize())).stream()
                .collect(
                        Collectors.groupingBy(
                                PendingNotification::channel,
                                () -> new EnumMap<>(NotificationChannel.class),
                                Collectors.toList()))
                .forEach(this::deliver);
    }

    private void deliver(NotificationChannel channel, List<PendingNotification> pending) {
        var devices =
                channel == NotificationChannel.PUSH
                        ? devicesOf(pending)
                        : Map.<UUID, List<String>>of();
        var outgoing =
                pending.stream()
                        .map(
                                notification ->
                                        new OutgoingNotification(
                                                notification.id(),
                                                notification.incidentId(),
                                                notification.message(),
                                                notification.recipientEmail(),
                                                devices.getOrDefault(
                                                        notification.recipientId(), List.of())))
                        .toList();
        Map<UUID, DeliveryOutcome> outcomes;
        try {
            outcomes = senders.get(channel).send(outgoing);
        } catch (RuntimeException e) {
            // A failure the sender did not classify (F8 of the plan of #373) is worth another
            // attempt for every row it was sending. The log names the ids, never the content.
            log.warn(
                    "Sending {} notifications {} failed: {}",
                    channel,
                    pending.stream().map(PendingNotification::id).toList(),
                    e.getClass().getSimpleName());
            outcomes = Map.of();
        }
        var now = clock.instant();
        for (var notification : pending) {
            try {
                writer.record(
                        notification,
                        outcomes.getOrDefault(notification.id(), DeliveryOutcome.unavailable()),
                        now);
            } catch (RuntimeException e) {
                // One failing write must not stop the others.
                log.warn(
                        "Recording the {} notification {} failed: {}",
                        channel,
                        notification.id(),
                        e.getClass().getSimpleName());
                try {
                    writer.fail(notification, now);
                } catch (RuntimeException failure) {
                    // The row stays pending and a later run sends it again (at least once,
                    // ADR-0031).
                    log.warn(
                            "Ending the {} notification {} as failed failed too: {}",
                            channel,
                            notification.id(),
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    /** The devices of each recipient at send time, in the order they were first registered. */
    private Map<UUID, List<String>> devicesOf(List<PendingNotification> pending) {
        var recipients = pending.stream().map(PendingNotification::recipientId).distinct().toList();
        return pushTokens.findTokensOf(recipients).stream()
                .collect(
                        Collectors.groupingBy(
                                RecipientToken::userId,
                                Collectors.mapping(RecipientToken::token, Collectors.toList())));
    }
}
