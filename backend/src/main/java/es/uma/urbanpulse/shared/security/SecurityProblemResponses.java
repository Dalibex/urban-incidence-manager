package es.uma.urbanpulse.shared.security;

import es.uma.urbanpulse.shared.web.MalformedRequestException;
import es.uma.urbanpulse.shared.web.RequestIdFilter;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the {@code 401} and {@code 403} of the security filters as RFC 9457 Problem Details, the
 * error body of the contract ({@code Unauthorized} and {@code Forbidden} in {@code
 * docs/api/openapi.yml}), and the {@code 400} of a request that Spring Security's firewall refuses
 * (a path with {@code //}, {@code ;} or {@code %2F}, or a header value or parameter name with a
 * control character, for example). Spring Security uses this bean for the firewall because it is
 * the only {@link RequestRejectedHandler} in the context.
 *
 * <p>These errors happen before the request reaches a controller, so a
 * {@code @RestControllerAdvice} never answers them. The firewall checks a header value or a
 * parameter name only when it is read, which may be inside Spring MVC: {@code ApiExceptionHandler}
 * rethrows that refusal so it reaches this class too. The detail is the same for every cause
 * (missing, malformed, expired or forged token): telling them apart would only help an attacker.
 */
@Component
class SecurityProblemResponses
        implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {

    private static final String UNAUTHORIZED_DETAIL =
            "The access token is missing, invalid or expired.";
    private static final String FORBIDDEN_DETAIL = "You are not allowed to perform this action.";
    // The property of the contract's Problem with the stable code of the error.
    private static final String CODE = "code";
    static final String REJECTED_DETAIL =
            "The method, path, headers or parameter names of the request are not allowed.";

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemResponses.class);

    private final JsonMapper jsonMapper;
    private final ObjectProvider<ObservationRegistry> observations;

    SecurityProblemResponses(
            JsonMapper jsonMapper, ObjectProvider<ObservationRegistry> observations) {
        this.jsonMapper = jsonMapper;
        this.observations = observations;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        // RFC 6750, section 3: a 401 for a bearer-protected resource names the scheme.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(
                request,
                response,
                withInstance(request, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_DETAIL));
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        write(request, response, withInstance(request, HttpStatus.FORBIDDEN, FORBIDDEN_DETAIL));
    }

    /**
     * No {@code instance}: the path is what the firewall refused, so it is not echoed back. The
     * {@code traceId} leads to the log line with it ({@code RequestIdFilter}).
     *
     * <p>Being the only handler replaces Spring Security's default one, so this one also does what
     * that did: it marks the current observation as failed, and logs the reason at debug level
     * only, because it can hold the value of a header.
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestRejectedException exception)
            throws IOException {
        var observation =
                observations.getIfUnique(() -> ObservationRegistry.NOOP).getCurrentObservation();
        if (observation != null) {
            observation.error(exception);
        }
        log.debug("Rejected request: {}", exception.getMessage());
        var problem = problem(HttpStatus.BAD_REQUEST, REJECTED_DETAIL);
        // The code of every request the API cannot read (docs/api.md).
        problem.put(CODE, MalformedRequestException.CODE);
        write(request, response, problem);
    }

    private static Map<String, Object> withInstance(
            HttpServletRequest request, HttpStatus status, String detail) {
        var problem = problem(status, detail);
        problem.put("instance", request.getRequestURI());
        return problem;
    }

    private static Map<String, Object> problem(HttpStatus status, String detail) {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        return problem;
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, Map<String, Object> problem)
            throws IOException {
        RequestIdFilter.current(request).ifPresent(id -> problem.put("traceId", id));

        response.setStatus((Integer) problem.get("status"));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
