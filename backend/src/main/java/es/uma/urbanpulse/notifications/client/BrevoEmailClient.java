package es.uma.urbanpulse.notifications.client;

import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import es.uma.urbanpulse.notifications.service.DeliveryOutcome;
import es.uma.urbanpulse.notifications.service.NotificationProperties;
import es.uma.urbanpulse.notifications.service.NotificationSender;
import es.uma.urbanpulse.notifications.service.OutgoingNotification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Email through Brevo's transactional email API (ADR-0031): one plain-text email per notification,
 * from {@code MAIL_FROM}, to the address of the recipient.
 */
@Component
class BrevoEmailClient implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailClient.class);

    static final String SEND = "/v3/smtp/email";
    private static final String API_KEY_HEADER = "api-key";
    private static final String SENDER_NAME = "UrbanPulse";
    private static final String SUBJECT = "Your incident has changed";

    private final RestClient http;
    private final JsonMapper json;
    private final String apiKey;
    private final String mailFrom;

    BrevoEmailClient(NotificationProperties properties, JsonMapper json) {
        this.http = ProviderHttp.restClient(properties.brevoBaseUrl(), properties);
        this.json = json;
        this.apiKey = properties.brevoApiKey();
        this.mailFrom = properties.mailFrom();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public Map<UUID, DeliveryOutcome> send(List<OutgoingNotification> batch) {
        var outcomes = new LinkedHashMap<UUID, DeliveryOutcome>();
        for (var notification : batch) {
            outcomes.put(notification.id(), send(notification));
        }
        return outcomes;
    }

    private DeliveryOutcome send(OutgoingNotification notification) {
        String body =
                json.writeValueAsString(
                        Map.of(
                                "sender", Map.of("name", SENDER_NAME, "email", mailFrom),
                                "to", List.of(Map.of("email", notification.email())),
                                "subject", SUBJECT,
                                "textContent", notification.message()));
        try {
            HttpStatusCode status =
                    http.post()
                            .uri(SEND)
                            .header(API_KEY_HEADER, apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(body)
                            .exchange((request, response) -> response.getStatusCode());
            return outcomeOf(notification.id(), status);
        } catch (RuntimeException e) {
            log.warn(
                    "Brevo failed for notification {}: {}",
                    notification.id(),
                    ProviderHttp.describe(e));
            return DeliveryOutcome.unavailable();
        }
    }

    private static DeliveryOutcome outcomeOf(UUID notification, HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return DeliveryOutcome.delivered();
        }
        log.warn("Brevo answered {} for notification {}", status.value(), notification);
        // Brevo refused the email itself (a 4xx other than 429): another attempt would be refused
        // too. Anything else is worth another attempt.
        return status.is4xxClientError() && !ProviderHttp.isTransient(status)
                ? DeliveryOutcome.failed()
                : DeliveryOutcome.unavailable();
    }
}
