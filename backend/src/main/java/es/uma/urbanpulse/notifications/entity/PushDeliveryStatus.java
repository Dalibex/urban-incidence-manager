package es.uma.urbanpulse.notifications.entity;

/**
 * Where a push message to one device stands: {@code ACCEPTED} by Expo with a ticket, waiting for
 * its receipt, which ends it as {@code DELIVERED} or {@code FAILED}.
 */
public enum PushDeliveryStatus {
    ACCEPTED,
    DELIVERED,
    FAILED
}
