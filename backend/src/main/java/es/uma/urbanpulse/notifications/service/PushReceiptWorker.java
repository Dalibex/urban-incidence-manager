package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.entity.PushDelivery;
import es.uma.urbanpulse.notifications.repository.PushDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reads Expo's receipts of the accepted push tickets (ADR-0031) and records them on each device
 * delivery. It never changes the notification: its status says whether Expo took the message.
 *
 * <p>Expo has a receipt about 15 minutes after the ticket and keeps it for 24 hours. A ticket with
 * no receipt yet waits for the next run; one older than 24 hours is marked as checked, still {@code
 * ACCEPTED}, because Expo will never answer it.
 */
@Service
public class PushReceiptWorker {

    private static final Logger log = LoggerFactory.getLogger(PushReceiptWorker.class);

    static final Duration RECEIPT_READY = Duration.ofMinutes(15);
    static final Duration KEPT_BY_EXPO = Duration.ofHours(24);
    // Expo takes up to 1000 ticket ids per call.
    static final int MAX_TICKETS_PER_CALL = 1000;

    private final NotificationProperties properties;
    private final PushDeliveryRepository deliveries;
    private final PushReceiptSource receipts;
    private final NotificationDeliveryWriter writer;
    private final Clock clock;

    public PushReceiptWorker(
            NotificationProperties properties,
            PushDeliveryRepository deliveries,
            PushReceiptSource receipts,
            NotificationDeliveryWriter writer,
            Clock clock) {
        this.properties = properties;
        this.deliveries = deliveries;
        this.receipts = receipts;
        this.writer = writer;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${urbanpulse.notifications.receipt-interval}",
            fixedDelayString = "${urbanpulse.notifications.receipt-interval}")
    void scheduledRun() {
        if (properties.enabled()) {
            readReceipts();
        }
    }

    /**
     * One run of the worker, with one call to Expo at most. Public for the tests, which call it
     * instead of waiting.
     */
    public void readReceipts() {
        // With push off nothing reaches Expo, and there may be no access token to ask with.
        if (!properties.pushEnabled()) {
            return;
        }
        var now = clock.instant();
        writer.giveUpReceiptsSentBefore(now.minus(KEPT_BY_EXPO), now);
        Map<String, PushDelivery> byTicket =
                deliveries
                        .findAwaitingReceipt(
                                now.minus(RECEIPT_READY).atOffset(ZoneOffset.UTC),
                                Limit.of(MAX_TICKETS_PER_CALL))
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        PushDelivery::getTicketId,
                                        Function.identity(),
                                        (first, second) -> first));
        if (byTicket.isEmpty()) {
            return;
        }
        List<PushReceipt> answered;
        try {
            answered = receipts.receiptsOf(List.copyOf(byTicket.keySet()));
        } catch (RuntimeException e) {
            // The tickets stay unchecked for the next run.
            log.warn(
                    "Expo receipts of {} tickets failed: {}",
                    byTicket.size(),
                    e.getClass().getSimpleName());
            return;
        }
        for (var receipt : answered) {
            var delivery = byTicket.get(receipt.ticketId());
            if (delivery != null) {
                writer.storeReceipt(delivery, receipt, now);
            }
        }
    }
}
