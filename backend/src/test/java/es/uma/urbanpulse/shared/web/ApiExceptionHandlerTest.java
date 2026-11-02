package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.shared.error.FieldError;
import es.uma.urbanpulse.shared.error.InvalidDataException;
import es.uma.urbanpulse.shared.error.RuleViolationException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
