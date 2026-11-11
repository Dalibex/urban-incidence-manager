package es.uma.urbanpulse.shared.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.env.Environment;

/**
 * Requests to the server that a {@code RANDOM_PORT} test starts, with the {@code X-Forwarded-*}
 * headers that nginx writes in the deployment (#200). They come from 127.0.0.1, a private address:
 * when the backend trusts a proxy, it trusts these requests as it trusts nginx inside the Compose
 * network.
 */
final class ForwardedRequests {

    static final String PASSWORD = "a-long-enough-password";

    // RFC 5737 reserves 198.51.100.0/24 for documentation: these addresses belong to no one.
    private static final String CLIENT_NETWORK = "198.51.100.";
    private static final int LAST_HOST = 254;
    private static final AtomicInteger nextHost = new AtomicInteger(1);

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    ForwardedRequests(Environment environment) {
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port");
    }

    /** An address no other request of the test run has used, so its rate limits start empty. */
    static String newClientAddress() {
        var host = nextHost.getAndIncrement();
        if (host > LAST_HOST) {
            throw new IllegalStateException("No client address left in " + CLIENT_NETWORK + "0/24");
        }
        return CLIENT_NETWORK + host;
    }

    static String newEmail() {
        return "forwarded-" + UUID.randomUUID() + "@example.com";
    }

    /** Signs up a citizen with {@link #PASSWORD}, as the client at {@code clientAddress}. */
    HttpResponse<String> signUp(String email, String clientAddress)
            throws IOException, InterruptedException {
        var body =
                """
                {"name": "Forwarded Citizen", "email": "%s", "password": "%s"}
                """
                        .formatted(email, PASSWORD);
        return post("/api/v1/registrations", body, Map.of("X-Forwarded-For", clientAddress));
    }

    /** Signs in as the client at {@code clientAddress}. */
    HttpResponse<String> signIn(String email, String password, String clientAddress)
            throws IOException, InterruptedException {
        var body =
                """
                {"email": "%s", "password": "%s"}
                """
                        .formatted(email, password);
        return post("/api/v1/sessions", body, Map.of("X-Forwarded-For", clientAddress));
    }

    HttpResponse<String> post(String path, String json, Map<String, String> headers)
            throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
