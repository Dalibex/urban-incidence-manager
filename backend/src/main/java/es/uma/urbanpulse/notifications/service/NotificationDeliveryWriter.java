package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.entity.DeliveryStatus;
import es.uma.urbanpulse.notifications.entity.PushDelivery;
import es.uma.urbanpulse.notifications.entity.PushDeliveryStatus;
import es.uma.urbanpulse.notifications.repository.NotificationRepository;
import es.uma.urbanpulse.notifications.repository.PendingNotification;
import es.uma.urbanpulse.notifications.repository.PushDeliveryRepository;
import es.uma.urbanpulse.notifications.repository.PushTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes of the delivery and receipt jobs, each in its own short transaction, so none is open
 * while a provider answers (AGENTS.md, section 6). A separate bean from the workers, because Spring
 * only applies {@code @Transactional} to calls from outside the bean.
 *
 * <p>Every write changes a row only if it is still {@code PENDING} (a notification) or {@code
 * ACCEPTED} (a delivery): with two runs, one write wins and the other changes nothing.
 */
@Component
class NotificationDeliveryWriter {

    /** Every attempt counts; a transient failure of the third one ends the row {@code FAILED}. */
    static final int MAX_ATTEMPTS = 3;

    /** The wait after the first and after the second attempt. */
    private static final List<Duration> RETRY_DELAYS =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

    /** Expo's code for a token that no longer reaches its device. */
    static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";

    private final NotificationRepository notifications;
    private final PushDeliveryRepository deliveries;
    private final PushTokenRepository pushTokens;

    NotificationDeliveryWriter(
            NotificationRepository notifications,
            PushDeliveryRepository deliveries,
            PushTokenRepository pushTokens) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.pushTokens = pushTokens;
    }

    /**
     * Stores the outcome of one attempt: a later attempt for a transient failure, while attempts
     * are left, or else the final status, with a delivery per device of a push notification. A
     * device Expo no longer knows loses its token.
     */
    @Transactional
    void record(PendingNotification notification, DeliveryOutcome outcome, Instant now) {
        var attempts = (short) (notification.attempts() + 1);
        if (outcome.result() == DeliveryOutcome.Result.UNAVAILABLE && attempts < MAX_ATTEMPTS) {
            notifications.retryLaterIfPending(
                    notification.id(), attempts, utc(now.plus(RETRY_DELAYS.get(attempts - 1))));
            return;
        }
        var status =
                outcome.result() == DeliveryOutcome.Result.DELIVERED
                        ? DeliveryStatus.DELIVERED
                        : DeliveryStatus.FAILED;
        if (notifications.endIfPending(notification.id(), status, attempts, utc(now)) == 0) {
            return;
        }
        for (var ticket : outcome.tickets()) {
            var delivery = new PushDelivery();
            delivery.setNotification(notifications.getReferenceById(notification.id()));
            delivery.setToken(ticket.token());
            delivery.setTicketId(ticket.ticketId());
            delivery.setStatus(
                    ticket.ok() ? PushDeliveryStatus.ACCEPTED : PushDeliveryStatus.FAILED);
            delivery.setError(ticket.error());
            delivery.setSentAt(utc(now));
            deliveries.save(delivery);
            if (DEVICE_NOT_REGISTERED.equals(ticket.error())) {
                pushTokens.deleteByToken(ticket.token());
            }
        }
    }

    /**
     * The fallback of a {@link #record} that threw: ends the row {@code FAILED} after this attempt,
     * so no run sends it again.
     */
    @Transactional
    void fail(PendingNotification notification, Instant now) {
        notifications.endIfPending(
                notification.id(),
                DeliveryStatus.FAILED,
                (short) (notification.attempts() + 1),
                utc(now));
    }

    /**
     * Marks as checked the deliveries accepted before {@code cutoff}: Expo no longer keeps their
     * receipts.
     */
    @Transactional
    int giveUpReceiptsSentBefore(Instant cutoff, Instant now) {
        return deliveries.giveUpSentBefore(utc(cutoff), utc(now));
    }

    /**
     * Stores Expo's receipt of a delivery. {@code DeviceNotRegistered} deletes its token, unless
     * the device registered it again after the message: then the device is alive.
     */
    @Transactional
    void storeReceipt(PushDelivery delivery, PushReceipt receipt, Instant now) {
        var status = receipt.ok() ? PushDeliveryStatus.DELIVERED : PushDeliveryStatus.FAILED;
        var stored = deliveries.endIfAccepted(delivery.getId(), status, receipt.error(), utc(now));
        if (stored == 1 && DEVICE_NOT_REGISTERED.equals(receipt.error())) {
            pushTokens.deleteIfRegisteredBefore(delivery.getToken(), delivery.getSentAt());
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
