package es.uma.urbanpulse.notifications.service;

import java.util.List;

/**
 * What a provider did with one notification.
 *
 * @param result {@code UNAVAILABLE} is a failure worth another attempt (no answer, a timeout,
 *     {@code 429}, {@code 5xx}, an answer that cannot be read); {@code FAILED} is final
 * @param tickets Expo's answer for each device of a push notification; empty otherwise
 */
public record DeliveryOutcome(Result result, List<PushTicket> tickets) {

    public enum Result {
        DELIVERED,
        FAILED,
        UNAVAILABLE
    }

    public DeliveryOutcome {
        tickets = List.copyOf(tickets);
    }

    public static DeliveryOutcome delivered() {
        return new DeliveryOutcome(Result.DELIVERED, List.of());
    }

    public static DeliveryOutcome failed() {
        return new DeliveryOutcome(Result.FAILED, List.of());
    }

    public static DeliveryOutcome unavailable() {
        return new DeliveryOutcome(Result.UNAVAILABLE, List.of());
    }

    /** {@code DELIVERED} if Expo took the message to at least one device, else {@code FAILED}. */
    public static DeliveryOutcome ofTickets(List<PushTicket> tickets) {
        var result = tickets.stream().anyMatch(PushTicket::ok) ? Result.DELIVERED : Result.FAILED;
        return new DeliveryOutcome(result, tickets);
    }
}
