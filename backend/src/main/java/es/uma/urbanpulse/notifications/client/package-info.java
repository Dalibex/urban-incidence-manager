/**
 * Adapters of the notifications module to its providers: push through Expo, behind {@code
 * NotificationSender} and {@code PushReceiptSource}, and email through Brevo, behind {@code
 * NotificationSender}. They hold no state and own no table.
 *
 * <p>Decisions: ADR-0010, ADR-0031 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.notifications.client;
