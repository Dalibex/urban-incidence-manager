package es.uma.urbanpulse.notifications.service;

/** Expo's receipt of a ticket: delivered to the device, or an error code. */
public record PushReceipt(String ticketId, String error) {

    public boolean ok() {
        return error == null;
    }
}
