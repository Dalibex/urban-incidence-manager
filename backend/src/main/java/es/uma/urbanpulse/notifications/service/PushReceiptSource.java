package es.uma.urbanpulse.notifications.service;

import java.util.List;

/** Expo's receipts of the push tickets (ADR-0031). */
public interface PushReceiptSource {

    /**
     * @return the receipts Expo has for these tickets; a ticket without one yet is left out
     * @throws RuntimeException if Expo cannot be reached, refuses the call or answers something
     *     that cannot be read
     */
    List<PushReceipt> receiptsOf(List<String> ticketIds);
}
