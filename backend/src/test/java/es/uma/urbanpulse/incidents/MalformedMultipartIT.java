package es.uma.urbanpulse.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jayway.jsonpath.JsonPath;
import es.uma.urbanpulse.TestcontainersConfiguration;
import es.uma.urbanpulse.identity.entity.Role;
import es.uma.urbanpulse.shared.security.TestTokens;
import es.uma.urbanpulse.shared.storage.StorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * An upload whose multipart body the server cannot parse (#407) is the client's mistake: {@code
 * 400} {@code MALFORMED_REQUEST}, nothing stored and no {@code ERROR} line. The body is parsed by
 * the servlet container before any controller runs, which MockMvc skips, so these tests send raw
 * bytes to a real server. The file over the limit and the valid upload check that the other answers
 * of the same parsing did not change.
 *
 * <p>The rows are deleted after each test: the server commits in its own transactions.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "urbanpulse.storage.evidence.max-file-size=1KB")
@Import({TestcontainersConfiguration.class, TestTokens.class})
@ExtendWith(OutputCaptureExtension.class)
class MalformedMultipartIT {

    private static final String BOUNDARY = "XYZ";
    private static final byte[] JPEG = jpeg("a broken street light");
    // A JPEG header followed by 2 KB: over the 1 KB limit of this class.
    private static final byte[] OVERSIZED_JPEG = jpeg("x".repeat(2048));

    @TempDir static Path storageFolder;

    @DynamicPropertySource
    static void storeFilesInATemporaryFolder(DynamicPropertyRegistry registry) {
        registry.add("urbanpulse.storage.base-path", () -> storageFolder.toString());
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestTokens tokens;
    @MockitoSpyBean private StorageService storage;

    private UUID citizen;
    private UUID incident;

    @BeforeEach
    void createCitizenAndIncident() {
        citizen =
                jdbc.queryForObject(
                        "INSERT INTO users (name, email, password_hash, role)"
                                + " VALUES ('Test', ?, '{bcrypt}not-used', 'CITIZEN') RETURNING id",
                        UUID.class,
                        "citizen-" + UUID.randomUUID() + "@example.com");
        incident =
                jdbc.queryForObject(
                        "INSERT INTO incident (reporter_id, title, description, status, reported_at,"
                                + " updated_at) VALUES (?, 'Broken streetlight', 'The lamp is off.',"
                                + " 'REPORTED', ?, ?) RETURNING id",
                        UUID.class,
                        citizen,
                        Timestamp.from(Instant.parse("2026-11-02T09:00:00Z")),
                        Timestamp.from(Instant.parse("2026-11-02T09:00:00Z")));
    }

    @AfterEach
    void deleteTheRowsOfTheTest() {
        jdbc.update("DELETE FROM attachment WHERE incident_id = ?", incident);
        jdbc.update("DELETE FROM incident WHERE id = ?", incident);
        jdbc.update("DELETE FROM users WHERE id = ?", citizen);
    }

    @Test
    void aBodyWhoseBoundaryIsNeverClosedAnswers400(CapturedOutput output) throws Exception {
        var unclosed =
                ("--"
                                + BOUNDARY
                                + "\r\nContent-Disposition: form-data; name=\"file\";"
                                + " filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\nabc")
                        .getBytes(StandardCharsets.US_ASCII);

        var response = upload("multipart/form-data; boundary=" + BOUNDARY, unclosed);

        expectMalformed(response, output);
    }

    @Test
    void aMultipartContentTypeWithoutBoundaryAnswers400(CapturedOutput output) throws Exception {
        var response = upload("multipart/form-data", multipartBody("a.jpg", "image/jpeg", JPEG));

        expectMalformed(response, output);
    }

    @Test
    void aFileOverTheLimitStillAnswers413() throws Exception {
        var response =
                upload(
                        "multipart/form-data; boundary=" + BOUNDARY,
                        multipartBody("big.jpg", "image/jpeg", OVERSIZED_JPEG));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(
                        type ->
                                assertThat(type)
                                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("FILE_TOO_LARGE");
        assertNothingStored();
    }

    @Test
    void aValidUploadStillAnswers201() throws Exception {
        var response =
                upload(
                        "multipart/form-data; boundary=" + BOUNDARY,
                        multipartBody("street-light.jpg", "image/jpeg", JPEG));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(attachmentRows()).isEqualTo(1);
    }

    private void expectMalformed(HttpResponse<String> response, CapturedOutput output) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(
                        type ->
                                assertThat(type)
                                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertThat((String) JsonPath.read(response.body(), "$.code"))
                .isEqualTo("MALFORMED_REQUEST");
        assertThat((String) JsonPath.read(response.body(), "$.traceId"))
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertNothingStored();
        // The level column of the log pattern: a client's mistake is not a server failure.
        assertThat(output.getAll()).doesNotContain(" ERROR ");
    }

    private HttpResponse<String> upload(String contentType, byte[] body)
            throws IOException, InterruptedException {
        var port = environment.getRequiredProperty("local.server.port");
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/v1/incidents/"
                                                + incident
                                                + "/attachments"))
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(citizen, Role.CITIZEN))
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertNothingStored() {
        assertThat(attachmentRows()).isZero();
        verify(storage, never()).put(any(byte[].class));
    }

    private int attachmentRows() {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM attachment WHERE incident_id = ?",
                        Integer.class,
                        incident);
        return count == null ? 0 : count;
    }

    /** A well-formed body with one part named {@code file}, closed by its boundary. */
    private static byte[] multipartBody(String fileName, String contentType, byte[] content) {
        var body = new ByteArrayOutputStream();
        body.writeBytes(
                ("--"
                                + BOUNDARY
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + fileName
                                + "\"\r\nContent-Type: "
                                + contentType
                                + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private static byte[] jpeg(String rest) {
        var header = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        var tail = rest.getBytes(StandardCharsets.US_ASCII);
        var bytes = new byte[header.length + tail.length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(tail, 0, bytes, header.length, tail.length);
        return bytes;
    }
}
