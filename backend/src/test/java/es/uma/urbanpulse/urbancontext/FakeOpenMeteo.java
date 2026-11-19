package es.uma.urbanpulse.urbancontext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A fake Open-Meteo on the loopback interface, with the JDK {@code HttpServer}: no test reaches the
 * real one (#309). Each call takes the next scripted answer, or the default one when the script is
 * empty, and the server keeps the query of every call.
 */
final class FakeOpenMeteo implements AutoCloseable {

    /** The instant every answer stands for, as Open-Meteo writes it with {@code timezone=GMT}. */
    static final String OBSERVED_AT = "2026-11-18T09:45";

    record Answer(int status, String body, Duration delay) {

        Answer after(Duration wait) {
            return new Answer(status, body, wait);
        }
    }

    /** A complete answer: WMO code, wind at 10 m in km/h and precipitation in mm. */
    static Answer current(int weatherCode, double windKmh, double precipitationMm) {
        return json(
                String.format(
                        Locale.ROOT,
                        "\"weather_code\": %d, \"wind_speed_10m\": %.1f, \"precipitation\": %.1f",
                        weatherCode,
                        windKmh,
                        precipitationMm));
    }

    /** An answer with the given fields inside {@code current}, next to its time. */
    static Answer json(String currentFields) {
        String fields = currentFields.isEmpty() ? "" : ", " + currentFields;
        return new Answer(
                200,
                "{\"current\": {\"time\": \"" + OBSERVED_AT + "\"" + fields + "}}",
                Duration.ZERO);
    }

    static Answer httpStatus(int status) {
        return new Answer(status, "{\"error\": true, \"reason\": \"scripted\"}", Duration.ZERO);
    }

    static Answer body(String body) {
        return new Answer(200, body, Duration.ZERO);
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Queue<Answer> script = new ConcurrentLinkedQueue<>();
    private final List<String> queries = new ArrayList<>();
    private volatile Answer fallback;
    private volatile CountDownLatch gate;

    private FakeOpenMeteo() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        // One thread per call, so two runs at once (T14) are answered at once.
        server.setExecutor(threads);
        server.start();
        reset();
    }

    static FakeOpenMeteo start() {
        try {
            return new FakeOpenMeteo();
        } catch (IOException e) {
            throw new IllegalStateException("The fake Open-Meteo could not start", e);
        }
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** No script, a rainy answer by default, no calls kept and no gate. */
    void reset() {
        script.clear();
        synchronized (queries) {
            queries.clear();
        }
        fallback = current(61, 10.0, 0.4);
        gate = null;
    }

    /** The next calls take these answers, in order; after them, the default one. */
    void answerNext(Answer... answers) {
        script.addAll(List.of(answers));
    }

    void answerEveryCall(Answer answer) {
        fallback = answer;
    }

    /** No call is answered until {@code calls} calls have arrived, or 5 s have passed. */
    void holdAnswersUntil(int calls) {
        gate = new CountDownLatch(calls);
    }

    int calls() {
        synchronized (queries) {
            return queries.size();
        }
    }

    List<String> queries() {
        synchronized (queries) {
            return List.copyOf(queries);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }

    private void answer(HttpExchange exchange) throws IOException {
        synchronized (queries) {
            queries.add(exchange.getRequestURI().getRawQuery());
        }
        try {
            CountDownLatch waiting = gate;
            if (waiting != null) {
                waiting.countDown();
                waiting.await(5, TimeUnit.SECONDS);
            }
            Answer answer = script.poll();
            if (answer == null) {
                answer = fallback;
            }
            Thread.sleep(answer.delay().toMillis());
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // The client gave up first (a timeout): there is nobody left to answer.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
