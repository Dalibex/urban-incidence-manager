package es.uma.urbanpulse.shared.web;

import static es.uma.urbanpulse.shared.web.ForwardedRequests.newClientAddress;
import static es.uma.urbanpulse.shared.web.ForwardedRequests.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Acceptance test of #200 without a proxy, as in development, where clients reach the backend
 * directly: without {@code server.forward-headers-strategy}, the backend ignores the {@code
 * X-Forwarded-*} headers. If it read them, a client could skip the rate limits by sending a new
 * address in each request.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        // One sign-up per address: the second sign-up shows whether the header changed it.
        properties = "urbanpulse.security.sign-up-attempts.max=1")
@Import(TestcontainersConfiguration.class)
class ForwardedHeadersWithoutProxyIT {

    @Autowired private Environment environment;

    @Test
    void aClientCannotGetANewSignUpLimitBySendingAnotherAddress() throws Exception {
        var requests = new ForwardedRequests(environment);

        assertThat(requests.signUp(newEmail(), newClientAddress()).statusCode()).isEqualTo(201);
        assertThat(requests.signUp(newEmail(), newClientAddress()).statusCode()).isEqualTo(429);
    }
}
