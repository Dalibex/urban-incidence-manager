package es.uma.urbanpulse.notifications;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A scripted answer of a fake provider ({@link FakeExpo}, {@link FakeBrevo}): an HTTP status and
 * body, after an optional delay, or a connection closed with no answer at all (a network error).
 */
record ProviderAnswer(int status, String body, Duration delay, boolean dropped) {

    static ProviderAnswer httpStatus(int status) {
        return new ProviderAnswer(
                status,
                "{\"errors\": [{\"code\": \"SCRIPTED\", \"message\": \"scripted\"}]}",
                Duration.ZERO,
                false);
    }

    static ProviderAnswer okWith(String body) {
        return new ProviderAnswer(200, body, Duration.ZERO, false);
    }

    /** The connection is closed before any byte of an answer: the client sees a network error. */
    static ProviderAnswer droppedConnection() {
        return new ProviderAnswer(0, "", Duration.ZERO, true);
    }

    ProviderAnswer after(Duration wait) {
        return new ProviderAnswer(status, body, wait, dropped);
    }

    /**
     * Answers the exchange and closes it. A dropped connection writes nothing: closing the exchange
     * before its headers closes the connection.
     */
    void answer(HttpExchange exchange) {
        try {
            if (!dropped) {
                write(exchange);
            }
        } catch (IOException e) {
            // The client gave up first (a timeout): there is nobody left to answer.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private void write(HttpExchange exchange) throws IOException, InterruptedException {
        Thread.sleep(delay.toMillis());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
