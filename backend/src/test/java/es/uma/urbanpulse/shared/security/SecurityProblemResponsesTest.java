package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The firewall's handler replaces Spring Security's default one, so it must still mark the request
 * as failed in its observation: without it, the metrics and traces would count a refused request as
 * a success.
 */
class SecurityProblemResponsesTest {

    @Test
    void aRefusedRequestMarksTheCurrentObservationAsFailed() throws Exception {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new AnyContext());
        var responses =
                new SecurityProblemResponses(
                        JsonMapper.builder().build(),
                        new StaticListableBeanFactory(Map.of("observations", registry))
                                .getBeanProvider(ObservationRegistry.class));
        var refusal = new RequestRejectedException("The request was rejected");
        var observation = Observation.start("http.server.requests", registry);
        var response = new MockHttpServletResponse();

        try (var scope = observation.openScope()) {
            responses.handle(new MockHttpServletRequest(), response, refusal);
        } finally {
            observation.stop();
        }

        assertThat(observation.getContext().getError()).isSameAs(refusal);
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void aRefusedRequestWithoutAnObservationIsStillAnswered() throws Exception {
        var responses =
                new SecurityProblemResponses(
                        JsonMapper.builder().build(),
                        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
        var response = new MockHttpServletResponse();

        responses.handle(
                new MockHttpServletRequest(),
                response,
                new RequestRejectedException("The request was rejected"));

        assertThat(response.getStatus()).isEqualTo(400);
    }

    private static final class AnyContext implements ObservationHandler<Observation.Context> {
        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
