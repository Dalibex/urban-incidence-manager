package es.uma.urbanpulse.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, so a failed request can be found in the logs (Twelve-Factor XI). The
 * id is the client's {@code X-Request-ID} when it is safe to echo, or a new one; it is returned in
 * the {@code X-Request-ID} header of every response, written in every log line of the request (MDC
 * key {@code requestId}) and repeated as {@code traceId} in every Problem body.
 *
 * <p>It runs before the security filters, so their {@code 401} and {@code 403} carry it too. A
 * client value is only kept if it is 1 to 64 letters, digits, {@code .}, {@code _} or {@code -}:
 * anything else could forge log lines or headers, and is replaced without being written anywhere.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    static final String MDC_KEY = "requestId";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** The id of the request, once this filter has run. */
    public static Optional<String> current(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(ATTRIBUTE)).map(String.class::cast);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var id = accepted(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are reused: the next request on this one must not log with this id.
            MDC.remove(MDC_KEY);
        }
    }

    private static String accepted(String sent) {
        return sent != null && SAFE.matcher(sent).matches()
                ? sent
                : UUID.randomUUID().toString().replace("-", "");
    }
}
