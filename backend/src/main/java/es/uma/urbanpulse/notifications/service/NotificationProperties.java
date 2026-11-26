package es.uma.urbanpulse.notifications.service;

import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The delivery of the citizen's notifications (#373, ADR-0031), from the environment
 * (docs/deployment.md).
 *
 * @param enabled whether the scheduled delivery and receipt jobs run ({@code
 *     NOTIFICATIONS_ENABLED}). Off, status changes still create their {@code PENDING} rows
 * @param pushEnabled whether push goes to Expo ({@code NOTIFICATIONS_PUSH_ENABLED}); off, a push
 *     row is only logged
 * @param expoBaseUrl where Expo answers, so the tests point to a fake server
 * @param expoAccessToken Expo's access token ({@code EXPO_ACCESS_TOKEN}), required with push on
 * @param emailEnabled whether email goes to Brevo ({@code NOTIFICATIONS_EMAIL_ENABLED}); off, an
 *     email row is only logged
 * @param brevoBaseUrl where Brevo answers, so the tests point to a fake server
 * @param brevoApiKey Brevo's API key ({@code BREVO_API_KEY}), required with email on
 * @param mailFrom the sender address of the emails ({@code MAIL_FROM}), required with email on
 * @param proxy HTTP forward proxy the calls go through ({@code NOTIFICATIONS_PROXY}); null, or
 *     empty in the environment, calls the providers directly
 * @param timeout connection and read timeout of each call
 * @param interval time between two runs of the delivery job
 * @param receiptInterval time between two runs of the receipt job
 * @param batchSize notifications per run of the delivery job
 */
@ConfigurationProperties("urbanpulse.notifications")
public record NotificationProperties(
        boolean enabled,
        boolean pushEnabled,
        URI expoBaseUrl,
        String expoAccessToken,
        boolean emailEnabled,
        URI brevoBaseUrl,
        String brevoApiKey,
        String mailFrom,
        URI proxy,
        Duration timeout,
        Duration interval,
        Duration receiptInterval,
        int batchSize) {

    public NotificationProperties {
        // An unset NOTIFICATIONS_PROXY reaches the binder as an empty string.
        if (proxy != null && proxy.toString().isBlank()) {
            proxy = null;
        }
        if (expoBaseUrl == null || brevoBaseUrl == null) {
            throw new IllegalArgumentException(
                    "urbanpulse.notifications.expo-base-url and brevo-base-url are required");
        }
        if (pushEnabled && isBlank(expoAccessToken)) {
            throw new IllegalArgumentException(
                    "EXPO_ACCESS_TOKEN is required when NOTIFICATIONS_PUSH_ENABLED is true");
        }
        if (emailEnabled && isBlank(brevoApiKey)) {
            throw new IllegalArgumentException(
                    "BREVO_API_KEY is required when NOTIFICATIONS_EMAIL_ENABLED is true");
        }
        if (emailEnabled && isBlank(mailFrom)) {
            throw new IllegalArgumentException(
                    "MAIL_FROM is required when NOTIFICATIONS_EMAIL_ENABLED is true");
        }
        requirePositive(timeout, "urbanpulse.notifications.timeout");
        requirePositive(interval, "urbanpulse.notifications.interval");
        requirePositive(receiptInterval, "urbanpulse.notifications.receipt-interval");
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "urbanpulse.notifications.batch-size must be at least 1");
        }
    }

    /** Whether the rows of {@code channel} go to its provider, not only to the log. */
    public boolean sends(NotificationChannel channel) {
        return switch (channel) {
            case PUSH -> pushEnabled;
            case EMAIL -> emailEnabled;
        };
    }

    /** Keeps the token and the key out of logs and error messages that print the properties. */
    @Override
    public String toString() {
        return "NotificationProperties[enabled="
                + enabled
                + ", pushEnabled="
                + pushEnabled
                + ", expoBaseUrl="
                + expoBaseUrl
                + ", expoAccessToken=***"
                + ", emailEnabled="
                + emailEnabled
                + ", brevoBaseUrl="
                + brevoBaseUrl
                + ", brevoApiKey=***"
                + ", proxy="
                + proxy
                + ", timeout="
                + timeout
                + ", interval="
                + interval
                + ", receiptInterval="
                + receiptInterval
                + ", batchSize="
                + batchSize
                + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
