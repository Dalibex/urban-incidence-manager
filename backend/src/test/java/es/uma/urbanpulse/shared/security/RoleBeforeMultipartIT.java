package es.uma.urbanpulse.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * #286 with an upload over the size limit. Only a real server parses the multipart (MockMvc hands
 * the file to the controller as it is), and it does so before Spring MVC picks the controller: a
 * role that may not upload evidence gets {@code 403} because the security filters run before that,
 * while a role that may upload still gets {@code 413}.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "urbanpulse.storage.evidence.max-file-size=1KB")
@Import({TestcontainersConfiguration.class, TestTokens.class})
class RoleBeforeMultipartIT {

    private static final String BOUNDARY = "RoleBeforeMultipartIT";
    private static final byte[] OVER_THE_LIMIT = new byte[4 * 1024];

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired private Environment environment;
    @Autowired private TestTokens tokens;

    @Test
    void anAnalystWithAFileOverTheLimitGetsForbiddenNotContentTooLarge() throws Exception {
        var response = upload(Role.ANALYST);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(
                        type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    void aCitizenWithAFileOverTheLimitStillGetsContentTooLarge() throws Exception {
        assertThat(upload(Role.CITIZEN).statusCode()).isEqualTo(413);
    }

    private HttpResponse<String> upload(Role role) throws IOException, InterruptedException {
        var port = environment.getRequiredProperty("local.server.port");
        var path = "/api/v1/incidents/" + UUID.randomUUID() + "/attachments";
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", tokens.bearer(role))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(OVER_THE_LIMIT)))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(byte[] file) throws IOException {
        var body = new ByteArrayOutputStream();
        body.write(
                ("--"
                                + BOUNDARY
                                + "\r\nContent-Disposition: form-data; name=\"file\";"
                                + " filename=\"big.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        body.write(file);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }
}
