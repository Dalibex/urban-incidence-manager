package es.uma.urbanpulse.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The id of each request (#131, test cases T6 to T9 of #142). */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    // T6
    @ParameterizedTest
    @ValueSource(strings = {"a", "web-1730563200.42_retry", "4bf92f3577b34da6a3ce929d0e0e4736"})
    void aSafeClientIdIsKept(String sent) throws Exception {
        var seen = run(withHeader(sent));

        assertThat(seen.response().getHeader(RequestIdFilter.HEADER)).isEqualTo(sent);
        assertThat(seen.requestId()).isEqualTo(sent);
        assertThat(seen.mdc()).isEqualTo(sent);
    }

    // T7
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "with spaces",
                "line\r\nX-Injected: yes",
                "semi;colon",
                "ñ",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void anUnsafeClientIdIsReplaced(String sent) throws Exception {
        var seen = run(withHeader(sent));

        var id = seen.response().getHeader(RequestIdFilter.HEADER);
        assertThat(id).isNotEqualTo(sent).matches("[0-9a-f]{32}");
        assertThat(seen.requestId()).isEqualTo(id);
        assertThat(seen.mdc()).isEqualTo(id);
    }

    // T8
    @Test
    void eachRequestWithoutIdGetsADifferentOne() throws Exception {
        var first = run(new MockHttpServletRequest("GET", "/api/v1/categories"));
        var second = run(new MockHttpServletRequest("GET", "/api/v1/categories"));

        assertThat(first.requestId()).matches("[0-9a-f]{32}");
        assertThat(second.requestId()).matches("[0-9a-f]{32}").isNotEqualTo(first.requestId());
    }

    // T9
    @Test
    void theIdDoesNotStayInTheLoggingContextAfterTheRequest() throws Exception {
        run(withHeader("first-request"));

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        var next = run(new MockHttpServletRequest("GET", "/api/v1/categories"));
        assertThat(next.requestId()).isNotEqualTo("first-request");
    }

    @Test
    void theIdIsClearedEvenWhenTheRequestFails() {
        var request = withHeader("failing-request");

        try {
            filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> {
                        throw new IllegalStateException("boom");
                    });
        } catch (Exception expected) {
            // The handler answers it; here only the logging context matters.
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private static MockHttpServletRequest withHeader(String id) {
        var request = new MockHttpServletRequest("GET", "/api/v1/categories");
        request.addHeader(RequestIdFilter.HEADER, id);
        return request;
    }

    private Seen run(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var mdc = new AtomicReference<String>();
        var requestId = new AtomicReference<String>();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    mdc.set(MDC.get(RequestIdFilter.MDC_KEY));
                    requestId.set(RequestIdFilter.current(request).orElse(null));
                });
        return new Seen(response, requestId.get(), mdc.get());
    }

    private record Seen(MockHttpServletResponse response, String requestId, String mdc) {}
}
