package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.ServletWebRequest;

/** The Problem bodies of {@link ApiExceptionHandler} that no endpoint reaches yet. */
class ApiExceptionHandlerTest {

    private static final String PATH = "/api/v1/incidents/3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aStaleVersionIsAConflictWithItsCode() {
        var stale = new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID());

        var problem =
                handler.handleConcurrentModification(
                        stale, new ServletWebRequest(new MockHttpServletRequest("POST", PATH)));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "CONCURRENT_MODIFICATION");
        assertThat(problem.getType()).isEqualTo(URI.create("about:blank"));
        assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
    }
}
