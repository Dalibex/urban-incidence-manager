package es.uma.urbanpulse.shared.web;

import static es.uma.urbanpulse.shared.web.ForwardedRequests.PASSWORD;
import static es.uma.urbanpulse.shared.web.ForwardedRequests.newClientAddress;
import static es.uma.urbanpulse.shared.web.ForwardedRequests.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.identity.service.AccountSecurityProperties;
import es.uma.urbanpulse.shared.security.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Acceptance tests of #200 (T12, T14 and T15 of #144) for the backend behind nginx. With the
 * setting of the deployment Compose, Tomcat takes the client address, the scheme, the host and the
 * port from the {@code X-Forwarded-*} headers that nginx writes, so the rate limits tell clients
 * apart and the {@code Location} of a {@code 201} is the public address. The headers nginx must
 * never pass on from the client are tested on nginx itself.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            // SERVER_FORWARD_HEADERS_STRATEGY of the deployment Compose.
            "server.forward-headers-strategy=native",
            // One sign-up per address: T12 needs two requests instead of eleven.
            "urbanpulse.security.sign-up-attempts.max=1"
        })
@Import({TestcontainersConfiguration.class, TestTokens.class})
class ForwardedHeadersBehindProxyIT {

    private static final String PUBLIC_HOST = "urbanpulse.example";
    private static final String INCIDENT =
            """
            {"title": "Street light off", "description": "Reported through nginx.",
             "location": {"latitude": 36.7213, "longitude": -4.4214, "accuracy": 10.0}}
            """;

    @Autowired private Environment environment;
    @Autowired private TestTokens tokens;
    @Autowired private AccountSecurityProperties security;

    private ForwardedRequests requests;

    @BeforeEach
    void connect() {
        requests = new ForwardedRequests(environment);
    }

    // T12: the limit of one client is not the limit of another.
    @Test
    void eachClientAddressHasItsOwnSignUpLimit() throws Exception {
        var client = newClientAddress();
        assertThat(requests.signUp(newEmail(), client).statusCode()).isEqualTo(201);
        assertThat(requests.signUp(newEmail(), client).statusCode()).isEqualTo(429);

        assertThat(requests.signUp(newEmail(), newClientAddress()).statusCode()).isEqualTo(201);
    }

    // T14: failed sign-ins from one address block that address, not the person behind the email.
    @Test
    void failedSignInsFromOneAddressDoNotBlockTheSameEmailFromAnother() throws Exception {
        var email = newEmail();
        assertThat(requests.signUp(email, newClientAddress()).statusCode()).isEqualTo(201);
        var attacker = newClientAddress();
        for (var attempt = 0; attempt < security.signInAttempts().max(); attempt++) {
            assertThat(requests.signIn(email, "a-wrong-password", attacker).statusCode())
                    .isEqualTo(401);
        }
        assertThat(requests.signIn(email, PASSWORD, attacker).statusCode()).isEqualTo(429);

        assertThat(requests.signIn(email, PASSWORD, newClientAddress()).statusCode())
                .isEqualTo(201);
    }

    // T15: the address the client used, never backend:8080; the default port of the scheme is
    // left out.
    @ParameterizedTest
    @CsvSource({
        "http,  8000, http://urbanpulse.example:8000",
        "https, 443,  https://urbanpulse.example"
    })
    void theLocationOfACreatedIncidentIsThePublicAddress(
            String scheme, String port, String publicOrigin) throws Exception {
        var citizen = requests.signUp(newEmail(), newClientAddress());
        assertThat(citizen.statusCode()).isEqualTo(201);
        var citizenId = UUID.fromString(JsonPath.read(citizen.body(), "$.id"));

        var created =
                requests.post(
                        "/api/v1/incidents",
                        INCIDENT,
                        Map.of(
                                "Authorization", tokens.bearer(citizenId, Role.CITIZEN),
                                "X-Forwarded-For", newClientAddress(),
                                "X-Forwarded-Proto", scheme,
                                "X-Forwarded-Host", PUBLIC_HOST,
                                "X-Forwarded-Port", port));

        assertThat(created.statusCode()).isEqualTo(201);
        String incidentId = JsonPath.read(created.body(), "$.id");
        assertThat(created.headers().firstValue("Location"))
                .hasValue(publicOrigin + "/api/v1/incidents/" + incidentId);
    }
}
