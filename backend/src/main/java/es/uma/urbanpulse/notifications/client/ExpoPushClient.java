package es.uma.urbanpulse.notifications.client;

import es.uma.urbanpulse.notifications.client.ProviderHttp.UnusableAnswerException;
import es.uma.urbanpulse.notifications.entity.NotificationChannel;
import es.uma.urbanpulse.notifications.service.DeliveryOutcome;
import es.uma.urbanpulse.notifications.service.NotificationProperties;
import es.uma.urbanpulse.notifications.service.NotificationSender;
import es.uma.urbanpulse.notifications.service.OutgoingNotification;
import es.uma.urbanpulse.notifications.service.PushReceipt;
import es.uma.urbanpulse.notifications.service.PushReceiptSource;
import es.uma.urbanpulse.notifications.service.PushTicket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Push notifications through Expo's push service (ADR-0031): one message per device, the tickets
 * Expo answers at once, and the receipts it has about 15 minutes later.
 */
@Component
class ExpoPushClient implements NotificationSender, PushReceiptSource {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushClient.class);

    static final String SEND = "/--/api/v2/push/send";
    static final String RECEIPTS = "/--/api/v2/push/getReceipts";
    // Expo takes up to 100 messages per request.
    static final int MAX_MESSAGES_PER_REQUEST = 100;
    private static final String TITLE = "UrbanPulse";
    // Expo refuses a message over 4 KiB (MessageTooBig); the email keeps the whole text.
    static final int MAX_BODY_CHARS = 1000;
    private static final String ELLIPSIS = "…";
    // An error ticket or receipt without Expo's code.
    private static final String UNKNOWN_ERROR = "Unknown";

    private final RestClient http;
    private final JsonMapper json;
    private final String authorization;

    ExpoPushClient(NotificationProperties properties, JsonMapper json) {
        this.http = ProviderHttp.restClient(properties.expoBaseUrl(), properties);
        this.json = json;
        this.authorization = "Bearer " + properties.expoAccessToken();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    /**
     * Sends each notification to every device of its recipient. A request holds whole
     * notifications, so the tickets of one notification come from a single answer.
     */
    @Override
    public Map<UUID, DeliveryOutcome> send(List<OutgoingNotification> batch) {
        var outcomes = new LinkedHashMap<UUID, DeliveryOutcome>();
        var request = new ArrayList<OutgoingNotification>();
        var messages = 0;
        for (var notification : batch) {
            if (notification.pushTokens().isEmpty()) {
                // No device left at send time: nothing to send it to.
                outcomes.put(notification.id(), DeliveryOutcome.failed());
                continue;
            }
            var size = devicesOf(notification).size();
            if (messages + size > MAX_MESSAGES_PER_REQUEST) {
                outcomes.putAll(sendRequest(request));
                request.clear();
                messages = 0;
            }
            request.add(notification);
            messages += size;
        }
        if (!request.isEmpty()) {
            outcomes.putAll(sendRequest(request));
        }
        return outcomes;
    }

    @Override
    public List<PushReceipt> receiptsOf(List<String> ticketIds) {
        String body = json.writeValueAsString(Map.of("ids", ticketIds));
        JsonNode data =
                post(
                                RECEIPTS,
                                body,
                                status ->
                                        new UnusableAnswerException(
                                                "Expo answered " + status.value()))
                        .path("data");
        if (!data.isObject()) {
            throw new UnusableAnswerException("Expo answered receipts without data");
        }
        var receipts = new ArrayList<PushReceipt>();
        for (var ticketId : ticketIds) {
            JsonNode receipt = data.get(ticketId);
            // Expo leaves out a ticket it has no receipt for yet.
            if (receipt != null) {
                receipts.add(new PushReceipt(ticketId, errorOf(receipt)));
            }
        }
        return receipts;
    }

    /**
     * Sends the messages of these notifications in one request.
     *
     * @return the outcome of each one: {@code UNAVAILABLE} for all of them if Expo could not be
     *     reached or answered something that cannot be used, {@code FAILED} if it refused the
     *     request, and otherwise the outcome of their tickets
     */
    private Map<UUID, DeliveryOutcome> sendRequest(List<OutgoingNotification> request) {
        var outcomes = new LinkedHashMap<UUID, DeliveryOutcome>();
        var tokens = new ArrayList<String>();
        var messages = new ArrayList<Map<String, Object>>();
        for (var notification : request) {
            for (var token : devicesOf(notification)) {
                tokens.add(token);
                messages.add(message(token, notification));
            }
        }
        List<PushTicket> tickets;
        try {
            tickets = tickets(tokens, json.writeValueAsString(messages));
        } catch (RefusedException e) {
            log.warn("Expo refused notifications {}: {}", idsOf(request), e.getMessage());
            request.forEach(n -> outcomes.put(n.id(), DeliveryOutcome.failed()));
            return outcomes;
        } catch (RuntimeException e) {
            log.warn(
                    "Expo failed for notifications {}: {}",
                    idsOf(request),
                    ProviderHttp.describe(e));
            request.forEach(n -> outcomes.put(n.id(), DeliveryOutcome.unavailable()));
            return outcomes;
        }
        // Ticket i answers message i.
        var next = 0;
        for (var notification : request) {
            var count = devicesOf(notification).size();
            outcomes.put(
                    notification.id(),
                    DeliveryOutcome.ofTickets(tickets.subList(next, next + count)));
            next += count;
        }
        return outcomes;
    }

    private List<PushTicket> tickets(List<String> tokens, String body) {
        JsonNode data =
                post(
                                SEND,
                                body,
                                status ->
                                        ProviderHttp.isTransient(status)
                                                ? new UnusableAnswerException(
                                                        "Expo answered " + status.value())
                                                : new RefusedException(
                                                        "Expo answered " + status.value()))
                        .path("data");
        if (!data.isArray() || data.size() != tokens.size()) {
            throw new UnusableAnswerException(
                    "Expo answered a ticket list that does not match the messages");
        }
        var tickets = new ArrayList<PushTicket>(tokens.size());
        for (var i = 0; i < tokens.size(); i++) {
            JsonNode ticket = data.get(i);
            String error = errorOf(ticket);
            String id = null;
            if (error == null) {
                JsonNode ticketId = ticket.path("id");
                if (!ticketId.isString()) {
                    throw new UnusableAnswerException("Expo answered an ok ticket without an id");
                }
                id = ticketId.asString();
            }
            tickets.add(new PushTicket(tokens.get(i), id, error));
        }
        return tickets;
    }

    private JsonNode post(
            String path, String body, Function<HttpStatusCode, RuntimeException> onError) {
        return http.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange(
                        (request, response) -> {
                            if (response.getStatusCode().isError()) {
                                throw onError.apply(response.getStatusCode());
                            }
                            return json.readTree(ProviderHttp.boundedBody(response.getBody()));
                        });
    }

    /**
     * @return {@code null} for an {@code ok} ticket or receipt, else Expo's error code
     */
    private static String errorOf(JsonNode answer) {
        String status = answer.path("status").asString("");
        if ("ok".equals(status)) {
            return null;
        }
        if (!"error".equals(status)) {
            throw new UnusableAnswerException("Expo answered a status it does not document");
        }
        JsonNode code = answer.path("details").path("error");
        return code.isString() ? code.asString() : UNKNOWN_ERROR;
    }

    private static Map<String, Object> message(String token, OutgoingNotification notification) {
        return Map.of(
                "to",
                token,
                "title",
                TITLE,
                "body",
                shortened(notification.message()),
                "data",
                Map.of("incidentId", notification.incidentId().toString()));
    }

    // shortcut: a citizen with more than 100 devices gets the message on the first 100 only;
    // split a notification across requests if that ever happens.
    private static List<String> devicesOf(OutgoingNotification notification) {
        var tokens = notification.pushTokens();
        return tokens.size() > MAX_MESSAGES_PER_REQUEST
                ? tokens.subList(0, MAX_MESSAGES_PER_REQUEST)
                : tokens;
    }

    static String shortened(String text) {
        if (text.length() <= MAX_BODY_CHARS) {
            return text;
        }
        var end = MAX_BODY_CHARS - ELLIPSIS.length();
        // Never cut a character outside the BMP in half.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    private static List<UUID> idsOf(List<OutgoingNotification> request) {
        return request.stream().map(OutgoingNotification::id).toList();
    }

    /** Expo refused the request itself (a {@code 4xx} other than {@code 429}): final. */
    private static final class RefusedException extends RuntimeException {

        RefusedException(String message) {
            super(message);
        }
    }
}
