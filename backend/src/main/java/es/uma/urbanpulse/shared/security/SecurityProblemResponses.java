package es.uma.urbanpulse.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the {@code 401} and {@code 403} of the security filters as RFC 9457 Problem Details, the
 * error body of the contract ({@code Unauthorized} and {@code Forbidden} in {@code
 * docs/api/openapi.yml}).
 *
 * <p>These errors happen before the request reaches a controller, so a
 * {@code @RestControllerAdvice} never sees them. The detail is the same for every cause (missing,
 * malformed, expired or forged token): telling them apart would only help an attacker.
 */
@Component
class SecurityProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String UNAUTHORIZED_DETAIL =
            "The access token is missing, invalid or expired.";
    private static final String FORBIDDEN_DETAIL = "You are not allowed to perform this action.";

    private final JsonMapper jsonMapper;

    SecurityProblemResponses(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        // RFC 6750, section 3: a 401 for a bearer-protected resource names the scheme.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_DETAIL);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, FORBIDDEN_DETAIL);
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String detail)
            throws IOException {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
