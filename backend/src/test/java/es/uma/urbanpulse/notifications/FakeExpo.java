package es.uma.urbanpulse.notifications;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A fake Expo push service on the loopback interface, with the JDK {@code HttpServer}: no test
 * reaches the real one. It answers the two calls of the Expo push API that the backend makes:
 *
 * <ul>
 *   <li>{@value #SEND}: by default, one ticket per message, in the order of the messages: {@code
 *       ok} with the id {@link #ticketOf(String)}, or the error scripted for its token with {@link
 *       #failTicketOf(String, String)}. A scripted answer replaces the tickets.
 *   <li>{@value #RECEIPTS}: the receipt scripted for each ticket asked for; a ticket with no
 *       scripted receipt is left out of the answer, as Expo does while it has none yet.
 * </ul>
 *
 * <p>It keeps the {@code Authorization} header and the body of every call.
 */
final class FakeExpo implements AutoCloseable {

    static final String SEND = "/--/api/v2/push/send";
    static final String RECEIPTS = "/--/api/v2/push/getReceipts";

    record Call(String authorization, String body) {}

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Queue<ProviderAnswer> sendScript = new ConcurrentLinkedQueue<>();
    private final Map<String, String> ticketErrors = new ConcurrentHashMap<>();
    private final Map<String, String> receipts = new ConcurrentHashMap<>();
    private final List<Call> sends = new CopyOnWriteArrayList<>();
    private final List<Call> receiptCalls = new CopyOnWriteArrayList<>();
    private volatile ProviderAnswer sendFallback;

    private FakeExpo() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(SEND, this::send);
        server.createContext(RECEIPTS, this::receipts);
        server.setExecutor(threads);
        server.start();
    }

    static FakeExpo start() {
        try {
            return new FakeExpo();
        } catch (IOException e) {
            throw new IllegalStateException("The fake Expo could not start", e);
        }
    }

    /** The id of the {@code ok} ticket of the message sent to {@code token}. */
    static String ticketOf(String token) {
        return "ticket-" + token;
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** No script, no ticket errors, no receipts and no calls kept. */
    void reset() {
        sendScript.clear();
        sendFallback = null;
        ticketErrors.clear();
        receipts.clear();
        sends.clear();
        receiptCalls.clear();
    }

    /** The next sends take these answers, in order; after them, the default tickets. */
    void answerNextSend(ProviderAnswer... answers) {
        sendScript.addAll(List.of(answers));
    }

    void answerEverySend(ProviderAnswer answer) {
        sendFallback = answer;
    }

    /** The ticket of the message to {@code token} is an error with this Expo code. */
    void failTicketOf(String token, String error) {
        ticketErrors.put(token, error);
    }

    void receiptOk(String ticket) {
        receipts.put(ticket, "{\"status\": \"ok\"}");
    }

    void receiptError(String ticket, String error) {
        receipts.put(ticket, errorJson(error));
    }

    List<Call> sends() {
        return List.copyOf(sends);
    }

    List<Call> receiptCalls() {
        return List.copyOf(receiptCalls);
    }

    /** The {@code to} of every message of a send, in order. */
    static List<String> recipientsOf(Call send) {
        return messagesOf(send.body()).stream().map(message -> (String) message.get("to")).toList();
    }

    /** The {@code data.incidentId} of every message of a send, in order. */
    static List<Object> incidentIdsOf(Call send) {
        return messagesOf(send.body()).stream()
                .<Object>map(message -> ((Map<?, ?>) message.get("data")).get("incidentId"))
                .toList();
    }

    /** The ticket ids a receipts call asked for. */
    static List<String> ticketIdsOf(Call receiptCall) {
        return JsonPath.read(receiptCall.body(), "$.ids");
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }

    /** The messages of a send: Expo takes one message or an array of them. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messagesOf(String body) {
        Object parsed = JsonPath.parse(body).read("$");
        if (parsed instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of((Map<String, Object>) parsed);
    }

    private void send(HttpExchange exchange) {
        String body = read(exchange);
        sends.add(new Call(exchange.getRequestHeaders().getFirst("Authorization"), body));
        ProviderAnswer answer = sendScript.poll();
        if (answer == null) {
            answer = sendFallback;
        }
        if (answer == null) {
            answer = ProviderAnswer.okWith(tickets(body));
        }
        answer.answer(exchange);
    }

    private void receipts(HttpExchange exchange) {
        String body = read(exchange);
        receiptCalls.add(new Call(exchange.getRequestHeaders().getFirst("Authorization"), body));
        String data =
                ticketIdsOf(new Call(null, body)).stream()
                        .filter(receipts::containsKey)
                        .map(ticket -> "\"" + ticket + "\": " + receipts.get(ticket))
                        .collect(Collectors.joining(", "));
        ProviderAnswer.okWith("{\"data\": {" + data + "}}").answer(exchange);
    }

    private String tickets(String body) {
        String tickets =
                messagesOf(body).stream()
                        .map(message -> (String) message.get("to"))
                        .map(
                                token ->
                                        ticketErrors.containsKey(token)
                                                ? errorJson(ticketErrors.get(token))
                                                : "{\"status\": \"ok\", \"id\": \""
                                                        + ticketOf(token)
                                                        + "\"}")
                        .collect(Collectors.joining(", "));
        return "{\"data\": [" + tickets + "]}";
    }

    private static String errorJson(String error) {
        return "{\"status\": \"error\", \"message\": \"scripted\", \"details\": {\"error\": \""
                + error
                + "\"}}";
    }

    private static String read(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
