package es.uma.urbanpulse.notifications.client;

import es.uma.urbanpulse.notifications.service.NotificationProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The HTTP set-up and the answer classification both providers share, as in OpenMeteoClient. */
final class ProviderHttp {

    private static final int TOO_MANY_REQUESTS = 429;
    // The largest answer is Expo's for 1000 receipts, well under 1 MiB; the limit keeps a hostile
    // or misrouted answer from filling the memory of the backend.
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private ProviderHttp() {}

    static RestClient restClient(URI baseUrl, NotificationProperties properties) {
        // The JDK client never follows a redirect by default, so a 3xx is an answer it cannot read.
        var builder = HttpClient.newBuilder().connectTimeout(properties.timeout());
        var proxy = properties.proxy();
        if (proxy != null) {
            // The deployment has no other way out (ADR-0011). Unresolved, so the name is resolved
            // by the JDK when it connects.
            builder.proxy(
                    ProxySelector.of(
                            InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
        }
        var requests = new JdkClientHttpRequestFactory(builder.build());
        requests.setReadTimeout(properties.timeout());
        return RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(requests).build();
    }

    /**
     * No answer worth keeping now, but worth another attempt later: {@code 429} or a {@code 5xx}.
     */
    static boolean isTransient(HttpStatusCode status) {
        return status.value() == TOO_MANY_REQUESTS || status.is5xxServerError();
    }

    // Content-Length can be missing (chunked) or false, so the limit is on the bytes actually read.
    static String boundedBody(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new UnusableAnswerException("The provider answered more than the limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * What a log line may say about a failed call: the message of an {@link
     * UnusableAnswerException}, which never holds a token, an address or a text, or else the type
     * of the root cause (a timeout, a refused connection), whose message could.
     */
    static String describe(RuntimeException e) {
        if (e instanceof UnusableAnswerException) {
            return e.getMessage();
        }
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    /** An answer of a provider the backend cannot use. Its message holds no personal data. */
    static final class UnusableAnswerException extends RuntimeException {

        UnusableAnswerException(String message) {
            super(message);
        }
    }
}
