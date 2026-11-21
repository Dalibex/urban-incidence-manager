package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import es.uma.urbanpulse.shared.error.RuleViolationException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.MultipartStream;
import org.apache.tomcat.util.http.fileupload.impl.IOFileUploadException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * The Problem bodies of {@link ApiExceptionHandler} that no endpoint reaches yet, and the status of
 * each kind of domain error (#131, test cases T2 and T3 of #142).
 */
class ApiExceptionHandlerTest {

    private static final String PATH = "/api/v1/incidents/3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e";
    private static final String REQUEST_ID = "handler-test-1";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aStaleVersionIsAConflictWithItsCode() throws Exception {
        var stale = new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID());

        var problem = handler.handleConcurrentModification(stale, request());

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "CONCURRENT_MODIFICATION")
                .containsEntry(ApiExceptionHandler.TRACE_ID, REQUEST_ID);
        assertThat(problem.getType()).isEqualTo(URI.create("about:blank"));
        assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
    }

    // T2
    @Test
    void aRuleViolationIsAConflictWithItsCode() throws Exception {
        var problem =
                handler.handleDomain(
                        new TestRuleViolation("INVALID_TRANSITION", "Not from REPORTED."),
                        request());

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("Not from REPORTED.");
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "INVALID_TRANSITION")
                .containsEntry(ApiExceptionHandler.TRACE_ID, REQUEST_ID)
                .doesNotContainKey(ApiExceptionHandler.ERRORS);
        assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
    }

    // T2
    @Test
    void invalidDataIsA422WithEveryField() throws Exception {
        var fields =
                List.of(
                        new FieldError("reason", "This change needs a reason."),
                        new FieldError("departmentId", "The department is not active."));

        var problem = handler.handleDomain(new TestInvalidData(fields), request());

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getDetail()).isEqualTo("The request has 2 invalid fields.");
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "VALIDATION_FAILED")
                .containsEntry(ApiExceptionHandler.ERRORS, fields);
    }

    // T3
    @Test
    void beanValidationAndDomainErrorsHaveTheSameShape() throws Exception {
        var target = new Object();
        var binding = new BeanPropertyBindingResult(target, "request");
        binding.addError(
                new org.springframework.validation.FieldError(
                        "request", "password", "must be at least 8 characters"));
        var notValid =
                new MethodArgumentNotValidException(
                        new MethodParameter(
                                ApiExceptionHandlerTest.class.getDeclaredMethod("request"), -1),
                        binding);

        var beanProblem =
                (ProblemDetail)
                        handler.handleMethodArgumentNotValid(
                                        notValid,
                                        new HttpHeaders(),
                                        HttpStatus.UNPROCESSABLE_CONTENT,
                                        request())
                                .getBody();
        var domainProblem =
                handler.handleDomain(
                        new TestInvalidData(
                                List.of(
                                        new FieldError(
                                                "password", "must be at least 8 characters"))),
                        request());

        assertThat(beanProblem.getStatus()).isEqualTo(domainProblem.getStatus());
        assertThat(beanProblem.getDetail()).isEqualTo(domainProblem.getDetail());
        assertThat(beanProblem.getProperties()).isEqualTo(domainProblem.getProperties());
    }

    // T5 of #67: the server refuses a file over the multipart limit while reading the request,
    // before any controller runs; MockMvc does not apply that limit, so it is checked here.
    @Test
    void aFileOverTheMultipartLimitIs413WithItsCode() throws Exception {
        var response =
                handler.handleMaxUploadSizeExceededException(
                        new MaxUploadSizeExceededException(10 * 1024 * 1024),
                        new HttpHeaders(),
                        HttpStatus.CONTENT_TOO_LARGE,
                        request());

        var problem = (ProblemDetail) response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "FILE_TOO_LARGE")
                .containsEntry(ApiExceptionHandler.TRACE_ID, REQUEST_ID);
        assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
    }

    // #407: Spring wraps every multipart parse failure the same way; only a malformed body is the
    // client's.
    @Test
    void aMalformedMultipartBodyIs400WithItsCode() throws Exception {
        var malformed =
                multipartFailure(
                        new MultipartStream.MalformedStreamException("Stream ended unexpectedly"));

        var problem = handler.handleMalformedMultipart(malformed, request());

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "MALFORMED_REQUEST")
                .containsEntry(ApiExceptionHandler.TRACE_ID, REQUEST_ID);
    }

    // #407: the server failing to write the temporary file is not the client's mistake.
    @Test
    void aMultipartFailureOfTheServerIs500() throws Exception {
        var diskFull = multipartFailure(new IOException("No space left on device"));

        var problem = handler.handleMalformedMultipart(diskFull, request());

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).doesNotContain("space");
        assertThat(problem.getProperties()).doesNotContainKey(ApiExceptionHandler.CODE);
    }

    // #407: Tomcat reports a missing boundary as a plain FileUploadException without a cause, and
    // wraps other read errors, which may be the server's, in a plain one with their cause.
    @Test
    void aMissingBoundaryIs400AndAWrappedReadErrorIs500() throws Exception {
        var missingBoundary =
                new MultipartException(
                        "Failed to parse multipart servlet request",
                        new FileUploadException(
                                "the request was rejected because no multipart boundary was"
                                        + " found"));
        var wrapped =
                new MultipartException(
                        "Failed to parse multipart servlet request",
                        new FileUploadException("Read timed out", new IOException("timeout")));

        assertThat(handler.handleMalformedMultipart(missingBoundary, request()).getStatus())
                .isEqualTo(400);
        assertThat(handler.handleMalformedMultipart(wrapped, request()).getStatus()).isEqualTo(500);
    }

    // T4: 500
    @Test
    void anUnexpectedErrorCarriesTheIdAndNothingElse() throws Exception {
        var problem =
                handler.handleUnexpected(new IllegalStateException("SELECT secret"), request());

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).doesNotContain("SELECT");
        assertThat(problem.getProperties()).containsEntry(ApiExceptionHandler.TRACE_ID, REQUEST_ID);
    }

    /** A request that went through {@link RequestIdFilter} with {@link #REQUEST_ID}. */
    private ServletWebRequest request() throws Exception {
        var request = new MockHttpServletRequest("POST", PATH);
        request.addHeader(RequestIdFilter.HEADER, REQUEST_ID);
        var seen = new ServletWebRequest[1];
        new RequestIdFilter()
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> seen[0] = new ServletWebRequest(request));
        return seen[0];
    }

    // What Spring throws when Tomcat cannot read the parts: Tomcat wraps the error of reading the
    // body or writing the temporary file in an IOFileUploadException.
    private static MultipartException multipartFailure(IOException cause) {
        return new MultipartException(
                "Failed to parse multipart servlet request",
                new IOFileUploadException(
                        "Processing of multipart/form-data request failed.", cause));
    }

    private static final class TestRuleViolation extends RuleViolationException {
        TestRuleViolation(String code, String detail) {
            super(code, detail);
        }
    }

    private static final class TestInvalidData extends InvalidDataException {
        TestInvalidData(List<FieldError> fields) {
            super(fields);
        }
    }
}
