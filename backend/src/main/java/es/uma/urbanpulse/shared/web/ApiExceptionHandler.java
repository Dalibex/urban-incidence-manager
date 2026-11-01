package es.uma.urbanpulse.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error of the controllers into the contract's Problem Details body ({@code Problem} in
 * {@code docs/api/openapi.yml}, conventions in {@code docs/api.md}). Spring's own exceptions
 * (unsupported method, missing resource...) come from {@link ResponseEntityExceptionHandler}; this
 * class adds the stable codes, the {@code 422} with {@code errors} and a {@code 500} that reveals
 * nothing.
 *
 * <p>The {@code 401} and {@code 403} of the security filters are written by {@code
 * SecurityProblemResponses}, before any controller runs.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String ERRORS = "errors";
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        ex.code().ifPresent(code -> problem.setProperty(CODE, code));
        if (!ex.fieldErrors().isEmpty()) {
            problem.setProperty(ERRORS, ex.fieldErrors());
        }
        complete(problem, request);

        var headers = new HttpHeaders();
        ex.retryAfter()
                .ifPresent(
                        wait ->
                                headers.set(
                                        HttpHeaders.RETRY_AFTER,
                                        // Rounded up: 899.9 s announced as 899 would
                                        // send an obedient client straight into another 429.
                                        String.valueOf(
                                                Math.max(1, Math.ceilDiv(wait.toMillis(), 1000)))));
        return ResponseEntity.status(ex.status()).headers(headers).body(problem);
    }

    /**
     * Anything unexpected: logged with its stack trace, answered without it. Security exceptions
     * are rethrown so the filters answer them: a {@code @PreAuthorize} that fails must stay a
     * {@code 403}, not become a {@code 500} (docs/security.md).
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, WebRequest request) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Unexpected error answering {}", path(request), ex);
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Something went wrong on our side. Try again later.");
        complete(problem, request);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Map<String, String>> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        Map.of(
                                                "field",
                                                error.getField(),
                                                "message",
                                                String.valueOf(error.getDefaultMessage())))
                        .toList();
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        errors.size() == 1
                                ? "The request has 1 invalid field."
                                : "The request has " + errors.size() + " invalid fields.");
        problem.setProperty(CODE, "VALIDATION_FAILED");
        problem.setProperty(ERRORS, errors);
        return handleExceptionInternal(
                ex, problem, headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // The parser's message names Java types and positions: not for the client.
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "The request body is not valid JSON.");
        problem.setProperty(CODE, MalformedRequestException.CODE);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * A path or query value of the wrong type: a malformed UUID, an unknown status, a text page.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return malformedParameters(ex, List.of(String.valueOf(ex.getPropertyName())), request);
    }

    /** A query value out of its range, such as {@code size} over 100 ({@code docs/api.md}). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        var names =
                ex.getParameterValidationResults().stream()
                        .map(result -> result.getMethodParameter().getParameterName())
                        .map(String::valueOf)
                        .toList();
        return malformedParameters(ex, names, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // Spring's detail names its static resource handling, which tells the client nothing.
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND, "The resource does not exist.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.NOT_FOUND, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            complete(problem, request);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // Spring's detail names Java types and echoes the value: the parameter name is enough.
    private ResponseEntity<Object> malformedParameters(
            Exception ex, List<String> parameters, WebRequest request) {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "Invalid value of the parameter " + String.join(", ", parameters) + ".");
        problem.setProperty(CODE, MalformedRequestException.CODE);
        return handleExceptionInternal(
                ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Fills what the contract requires and Spring leaves empty: {@code type} is required in {@code
     * Problem}, and Spring Framework 7 no longer defaults it to {@code about:blank}.
     */
    private static void complete(ProblemDetail problem, WebRequest request) {
        if (problem.getType() == null) {
            problem.setType(ABOUT_BLANK);
        }
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(path(request)));
        }
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : "";
    }
}
