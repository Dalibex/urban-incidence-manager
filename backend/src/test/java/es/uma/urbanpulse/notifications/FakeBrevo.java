package es.uma.urbanpulse.notifications;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A fake Brevo transactional email API on the loopback interface, with the JDK {@code HttpServer}:
 * no test reaches the real one. {@value #SEND} answers {@code 201} with a message id, as Brevo
 * does, unless a test scripts another answer. It keeps the {@code api-key} header and the body of
 * every call.
 */
final class FakeBrevo implements AutoCloseable {

    static final String SEND = "/v3/smtp/email";

    record Call(String apiKey, String body) {}

    private static final ProviderAnswer CREATED =
            new ProviderAnswer(
                    201, "{\"messageId\": \"<scripted@fake.brevo>\"}", Duration.ZERO, false);

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Queue<ProviderAnswer> script = new ConcurrentLinkedQueue<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile ProviderAnswer fallback = CREATED;

    private FakeBrevo() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(SEND, this::send);
        server.setExecutor(threads);
        server.start();
    }

    static FakeBrevo start() {
        try {
            return new FakeBrevo();
        } catch (IOException e) {
            throw new IllegalStateException("The fake Brevo could not start", e);
        }
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** No script, {@code 201} for every call and no calls kept. */
    void reset() {
        script.clear();
        fallback = CREATED;
        calls.clear();
    }

    /** The next calls take these answers, in order; after them, the default one. */
    void answerNext(ProviderAnswer... answers) {
        script.addAll(List.of(answers));
    }

    void answerEveryCall(ProviderAnswer answer) {
        fallback = answer;
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }

    private void send(HttpExchange exchange) {
        String body;
        try {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            body = "";
        }
        calls.add(new Call(exchange.getRequestHeaders().getFirst("api-key"), body));
        ProviderAnswer answer = script.poll();
        (answer == null ? fallback : answer).answer(exchange);
    }
}
