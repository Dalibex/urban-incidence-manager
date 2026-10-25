/**
 * Notifications module: notifications created on incident status changes and their delivery status.
 * Channels {@code EMAIL} (Brevo), {@code SMS} (Twilio) and {@code APP} (stored only), each behind a
 * {@code NotificationSender} (ADR-0019).
 *
 * <p>Decisions: ADR-0010, ADR-0019 ({@code docs/adr/}).
 */
package es.uma.urbanpulse.notifications;
