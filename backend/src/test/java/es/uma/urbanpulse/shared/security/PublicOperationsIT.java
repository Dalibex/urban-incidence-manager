package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Sign-up, sign-in and refresh on a real server. Only a real servlet container renders errors in a
 * second dispatch to {@code /error}, which MockMvc skips: these tests caught errors that reached
 * the client as 401.
 *
 * <p>The body {@code {}} is not valid for any of them, so the expected answer is {@code 422}: the
 * request went through the security filters and reached the controller.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PublicOperationsIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired private Environment environment;

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/registrations", "/api/v1/sessions", "/api/v1/sessions/refresh"})
    void areReachableWithoutToken(String path) throws Exception {
        assertThat(post(path, Optional.empty()).statusCode()).isEqualTo(422);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/registrations", "/api/v1/sessions", "/api/v1/sessions/refresh"})
    void ignoreAnExpiredOrInvalidAccessTokenSentAlong(String path) throws Exception {
        // A client may attach its expired access token to the refresh request: it must not fail.
        assertThat(post(path, Optional.of("Bearer not-a-jwt")).statusCode()).isEqualTo(422);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/incidents", "/api/v1/sessions/current"})
    void otherOperationsStillAnswer401WithTheirOwnPath(String path) throws Exception {
        var response = post(path, Optional.empty());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"instance\":\"" + path + "\"");
    }

    private HttpResponse<String> post(String path, Optional<String> authorization)
            throws IOException, InterruptedException {
        var port = environment.getRequiredProperty("local.server.port");
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"));
        authorization.ifPresent(value -> request.header("Authorization", value));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
