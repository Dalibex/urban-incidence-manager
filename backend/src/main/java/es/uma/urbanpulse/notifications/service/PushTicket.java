package es.uma.urbanpulse.notifications.service;

/**
 * Expo's answer for the message to one device: a ticket id, or an error code such as {@code
 * DeviceNotRegistered}.
 */
public record PushTicket(String token, String ticketId, String error) {

    public boolean ok() {
        return error == null;
    }
}
